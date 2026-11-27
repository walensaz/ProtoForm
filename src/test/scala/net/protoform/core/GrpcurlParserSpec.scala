package net.protoform.core

import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import net.protoform.core.call.GrpcurlParser
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GrpcurlParserSpec extends AnyFunSuite with Matchers {

  test("parses plaintext multi-line grpcurl command with headers, Bearer auth, and escaped JSON") {
    val cmd =
      """grpcurl -plaintext \
        |  -H "Authorization: Bearer my-jwt-token" \
        |  -H 'x-tenant-id: acme' \
        |  -d '{"note": "it'\''s working", "count": 3}' \
        |  localhost:50051 acme.OrderService/CreateOrder""".stripMargin

    val parsed = GrpcurlParser.parse(cmd).fold(err => fail(err), identity)
    parsed.endpoint shouldBe Some("localhost:50051")
    parsed.useTls shouldBe false
    parsed.insecureSkipTlsVerify shouldBe false
    parsed.fullMethodName shouldBe Some("acme.OrderService/CreateOrder")
    parsed.requestJson shouldBe """{"note": "it's working", "count": 3}"""

    val (preset, remaining) = parsed.extractAuthAndHeaders
    preset shouldBe AuthPreset.BearerToken("my-jwt-token")
    remaining shouldBe List(MetadataHeader("x-tenant-id", "acme", enabled = true))
  }

  test("parses TLS, mTLS flags, authority, IPv6 endpoint, and dot-separated method") {
    val cmd =
      """grpcurl -insecure -cacert /certs/ca.pem -cert /certs/client.crt -key /certs/client.key \
        |  -authority internal.svc -H="x-trace: abc:123" \
        |  [::1]:8443 acme.UserService.GetUser""".stripMargin

    val parsed = GrpcurlParser.parse(cmd).fold(err => fail(err), identity)
    parsed.useTls shouldBe true
    parsed.insecureSkipTlsVerify shouldBe true
    parsed.caCertPath shouldBe Some("/certs/ca.pem")
    parsed.clientCertPath shouldBe Some("/certs/client.crt")
    parsed.clientKeyPath shouldBe Some("/certs/client.key")
    parsed.overrideAuthority shouldBe Some("internal.svc")
    parsed.headers shouldBe List(MetadataHeader("x-trace", "abc:123", enabled = true))
    parsed.endpoint shouldBe Some("[::1]:8443")
    parsed.fullMethodName shouldBe Some("acme.UserService/GetUser")
  }

  test("extracts Basic auth header and rejects unterminated quotes") {
    val b64 = java.util.Base64.getEncoder.encodeToString("alice:s3cr3t".getBytes("UTF-8"))
    val parsed = GrpcurlParser.parse(s"""grpcurl -H "Authorization: Basic $b64" localhost:9000 pkg.Svc/Call""").toOption.get
    parsed.extractAuthAndHeaders._1 shouldBe AuthPreset.BasicAuth("alice", "s3cr3t")

    GrpcurlParser.parse("""grpcurl -d '{"unclosed": true localhost:9000 pkg.Svc/Call""").isLeft shouldBe true
    GrpcurlParser.parse("""grpcurl -d -plaintext""").isRight shouldBe true
    GrpcurlParser.parse("""grpcurl -H "bad-header" localhost:9000 pkg.Svc/Call""").isLeft shouldBe true
    GrpcurlParser.parse("   ").isLeft shouldBe true
  }
}
