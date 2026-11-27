package net.protoform.core

import net.protoform.core.model.EndpointTarget
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class EndpointTargetSpec extends AnyFunSuite with Matchers {

  test("EndpointTarget.parse should parse host and port correctly") {
    val result = EndpointTarget.parse("localhost:50051", useTls = false)
    result.isRight shouldBe true
    val target = result.toOption.get
    target.host shouldBe "localhost"
    target.port shouldBe 50051
    target.useTls shouldBe false
    target.uriString shouldBe "http://localhost:50051"
  }

  test("EndpointTarget.parse should handle TLS flag and URI prefix stripping") {
    val result = EndpointTarget.parse("https://grpc.example.com:443", useTls = true)
    result.isRight shouldBe true
    val target = result.toOption.get
    target.host shouldBe "grpc.example.com"
    target.port shouldBe 443
    target.useTls shouldBe true
    target.uriString shouldBe "https://grpc.example.com:443"
  }

  test("EndpointTarget.parse should assign default port when omitted") {
    val plaintext = EndpointTarget.parse("api.service.local", useTls = false).toOption.get
    plaintext.port shouldBe 80

    val tls = EndpointTarget.parse("api.service.local", useTls = true).toOption.get
    tls.port shouldBe 443
  }

  test("EndpointTarget.parse should reject invalid port numbers") {
    val invalidNegative = EndpointTarget.parse("localhost:-1")
    invalidNegative.isLeft shouldBe true

    val invalidOverMax = EndpointTarget.parse("localhost:70000")
    invalidOverMax.isLeft shouldBe true

    val notANumber = EndpointTarget.parse("localhost:abc")
    notANumber.isLeft shouldBe true
  }

  test("EndpointTarget.parse should reject empty hosts") {
    EndpointTarget.parse(":50051").isLeft shouldBe true
    EndpointTarget.parse("  :50051").isLeft shouldBe true
    EndpointTarget.parse("http://:50051").isLeft shouldBe true
    EndpointTarget.parse("").isLeft shouldBe true
    EndpointTarget.parse("[]:50051").isLeft shouldBe true
  }

  test("EndpointTarget.parse should support IPv6 bracketed hosts") {
    val withPort = EndpointTarget.parse("[::1]:50051", useTls = false).toOption.get
    withPort.host shouldBe "::1"
    withPort.port shouldBe 50051
    withPort.uriString shouldBe "http://[::1]:50051"
    withPort.authority shouldBe "[::1]:50051"

    val withScheme = EndpointTarget.parse("http://[::1]:50051", useTls = false).toOption.get
    withScheme.host shouldBe "::1"
    withScheme.port shouldBe 50051
    withScheme.uriString shouldBe "http://[::1]:50051"

    val withoutPort = EndpointTarget.parse("[::1]", useTls = false).toOption.get
    withoutPort.host shouldBe "::1"
    withoutPort.port shouldBe 80
    withoutPort.uriString shouldBe "http://[::1]:80"

    val withoutPortTls = EndpointTarget.parse("[::1]", useTls = true).toOption.get
    withoutPortTls.host shouldBe "::1"
    withoutPortTls.port shouldBe 443
    withoutPortTls.uriString shouldBe "https://[::1]:443"

    EndpointTarget.parse("[::1").isLeft shouldBe true
  }

  test("EndpointTarget.parse should strip trailing slashes") {
    val withSlash = EndpointTarget.parse("localhost:50051/", useTls = false).toOption.get
    withSlash.host shouldBe "localhost"
    withSlash.port shouldBe 50051

    val withMultipleSlashes = EndpointTarget.parse("https://grpc.example.com:443///", useTls = true).toOption.get
    withMultipleSlashes.host shouldBe "grpc.example.com"
    withMultipleSlashes.port shouldBe 443

    val ipv6WithSlash = EndpointTarget.parse("http://[::1]:50051/", useTls = false).toOption.get
    ipv6WithSlash.host shouldBe "::1"
    ipv6WithSlash.port shouldBe 50051
  }
}
