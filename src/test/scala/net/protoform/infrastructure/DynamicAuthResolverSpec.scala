package net.protoform.infrastructure

import net.protoform.core.auth.AuthPreset
import net.protoform.infrastructure.auth.DynamicAuthResolver
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.util.Base64

class DynamicAuthResolverSpec extends AnyFunSuite with Matchers {

  private val resolver = new DynamicAuthResolver()

  test("resolves HS256 SignedJwt with claims and validates compact JWS structure") {
    val preset = AuthPreset.SignedJwt(
      algorithm = "HS256",
      keyOrSecret = "super-secret-signing-key",
      issuer = "protoform-qa",
      subject = "user-42",
      audience = "orders.v1",
      customClaimsJson = """{"role":"admin"}""",
      ttlSeconds = 600
    )

    val headers = resolver.resolveHeaders(preset).fold(e => fail(e.message), identity)
    headers should have size 1
    headers.head.key shouldBe "authorization"
    headers.head.value should startWith("Bearer ")

    val jwt = headers.head.value.stripPrefix("Bearer ")
    val parts = jwt.split("\\.")
    parts should have length 3

    val headerJson = new String(Base64.getUrlDecoder.decode(parts(0)), StandardCharsets.UTF_8)
    val payloadJson = new String(Base64.getUrlDecoder.decode(parts(1)), StandardCharsets.UTF_8)
    headerJson should include(""""alg":"HS256"""")
    payloadJson should include(""""iss":"protoform-qa"""")
    payloadJson should include(""""sub":"user-42"""")
    payloadJson should include(""""aud":"orders.v1"""")
    payloadJson should include(""""role":"admin"""")
  }

  test("resolves RS256 SignedJwt using PKCS#8 PEM private key") {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val pkcs8B64 = Base64.getEncoder.encodeToString(kpg.generateKeyPair().getPrivate.getEncoded)
    val pem = s"-----BEGIN PRIVATE KEY-----\n$pkcs8B64\n-----END PRIVATE KEY-----"

    val preset = AuthPreset.SignedJwt(algorithm = "RS256", keyOrSecret = pem, issuer = "protoform", ttlSeconds = 300)
    val headers = resolver.resolveHeaders(preset).fold(e => fail(e.message), identity)
    val jwt = headers.head.value.stripPrefix("Bearer ")
    jwt.split("\\.") should have length 3
  }

  test("rejects missing secret/key or missing Google Cloud service account path cleanly") {
    resolver.resolveHeaders(AuthPreset.SignedJwt(algorithm = "HS256", keyOrSecret = "")).isLeft shouldBe true
    resolver.resolveHeaders(AuthPreset.GoogleCloud("AccessToken", Some("/nonexistent/sa-key.json"))).isLeft shouldBe true
  }
}
