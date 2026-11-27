package net.protoform.core

import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.Base64

class AuthPresetSpec extends AnyFunSuite with Matchers {

  test("AuthPreset.None should produce no headers") {
    AuthPreset.None.toHeaders shouldBe empty
  }

  test("AuthPreset.ApiKey should produce header with custom key name") {
    val preset = AuthPreset.ApiKey("x-custom-key", "secret123")
    val headers = preset.toHeaders
    headers should have size 1
    headers.head.key shouldBe "x-custom-key"
    headers.head.value shouldBe "secret123"
  }

  test("AuthPreset.BearerToken should produce Authorization header") {
    val preset = AuthPreset.BearerToken("my-jwt-token")
    val headers = preset.toHeaders
    headers should have size 1
    headers.head.key shouldBe "authorization"
    headers.head.value shouldBe "Bearer my-jwt-token"
  }

  test("AuthPreset.BasicAuth should correctly base64 encode credentials") {
    val preset = AuthPreset.BasicAuth("admin", "p@ssword")
    val headers = preset.toHeaders
    headers should have size 1
    headers.head.key shouldBe "authorization"

    val expectedBase64 = Base64.getEncoder.encodeToString("admin:p@ssword".getBytes("UTF-8"))
    headers.head.value shouldBe s"Basic $expectedBase64"
  }

  test("AuthPreset.Custom should filter out disabled headers") {
    val preset = AuthPreset.Custom(List(
      MetadataHeader("x-tenant-id", "t-100", enabled = true),
      MetadataHeader("x-debug", "true", enabled = false),
      MetadataHeader("x-request-id", "req-abc", enabled = true)
    ))
    val headers = preset.toHeaders
    headers should have size 2
    headers.map(_.key) shouldBe List("x-tenant-id", "x-request-id")
  }
}
