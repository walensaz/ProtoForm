package net.protoform.infrastructure

import io.grpc.{CallOptions, Channel, ClientCall, Metadata, MethodDescriptor}
import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import net.protoform.infrastructure.auth.MetadataAuthInterceptor
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.util.Base64

class MetadataAuthInterceptorSpec extends AnyFunSuite with Matchers {

  private val dummyMethod: MethodDescriptor[String, String] = {
    val marshaller = new MethodDescriptor.Marshaller[String] {
      override def stream(value: String): InputStream = new ByteArrayInputStream(Array.emptyByteArray)
      override def parse(stream: InputStream): String = ""
    }
    MethodDescriptor.newBuilder[String, String]()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName("test.Service/Ping")
      .setRequestMarshaller(marshaller)
      .setResponseMarshaller(marshaller)
      .build()
  }

  private def captureMetadata(headers: List[MetadataHeader]): Metadata = {
    val captured = new Metadata()
    val stubCall = new ClientCall[String, String] {
      override def start(responseListener: ClientCall.Listener[String], metadata: Metadata): Unit = {
        captured.merge(metadata)
      }
      override def request(numMessages: Int): Unit = ()
      override def cancel(message: String, cause: Throwable): Unit = ()
      override def halfClose(): Unit = ()
      override def sendMessage(message: String): Unit = ()
    }
    val stubChannel = new Channel {
      override def newCall[ReqT, RespT](
        methodDescriptor: MethodDescriptor[ReqT, RespT],
        callOptions: CallOptions
      ): ClientCall[ReqT, RespT] = {
        stubCall.asInstanceOf[ClientCall[ReqT, RespT]]
      }
      override def authority(): String = "localhost"
    }

    val interceptor = new MetadataAuthInterceptor(headers)
    val intercepted = interceptor.interceptCall(dummyMethod, CallOptions.DEFAULT, stubChannel)
    intercepted.start(new ClientCall.Listener[String] {}, new Metadata())
    captured
  }

  test("attaches trimmed lowercased ASCII headers and ignores disabled headers") {
    val headers = List(
      MetadataHeader("  X-Tenant-Id  ", "  tenant-42  ", enabled = true),
      MetadataHeader("x-disabled", "should-not-appear", enabled = false)
    )
    val metadata = captureMetadata(headers)

    val tenantKey = Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER)
    val disabledKey = Metadata.Key.of("x-disabled", Metadata.ASCII_STRING_MARSHALLER)
    metadata.get(tenantKey) shouldBe "tenant-42"
    metadata.get(disabledKey) shouldBe null
  }

  test("handles -bin headers with surrounding whitespace and mixed case") {
    val payload = "binary-payload"
    val encoded = Base64.getEncoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
    val headers = List(
      MetadataHeader("  X-Trace-Bin  ", s"  $encoded  ", enabled = true),
      MetadataHeader("X-Fallback-BIN", "not!valid@base64", enabled = true)
    )
    val metadata = captureMetadata(headers)

    val traceKey = Metadata.Key.of("x-trace-bin", Metadata.BINARY_BYTE_MARSHALLER)
    val fallbackKey = Metadata.Key.of("x-fallback-bin", Metadata.BINARY_BYTE_MARSHALLER)
    new String(metadata.get(traceKey), StandardCharsets.UTF_8) shouldBe payload
    new String(metadata.get(fallbackKey), StandardCharsets.UTF_8) shouldBe "not!valid@base64"
  }

  test("skips invalid metadata keys without throwing while preserving valid headers") {
    val headers = List(
      MetadataHeader("   ", "blank", enabled = true),
      MetadataHeader("invalid header!", "oops", enabled = true),
      MetadataHeader("-bin", "aGVsbG8=", enabled = true),
      MetadataHeader(":authority", "spoof", enabled = true),
      MetadataHeader("x-valid-header", "ok", enabled = true)
    )
    val metadata = captureMetadata(headers)

    val validKey = Metadata.Key.of("x-valid-header", Metadata.ASCII_STRING_MARSHALLER)
    metadata.get(validKey) shouldBe "ok"
    metadata.keys().size() shouldBe 1
  }

  test("applies headers produced by AuthPreset variants") {
    val apiMeta = captureMetadata(AuthPreset.ApiKey("X-API-Key", "secret").toHeaders)
    apiMeta.get(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER)) shouldBe "secret"

    val bearerMeta = captureMetadata(AuthPreset.BearerToken("tok-123").toHeaders)
    bearerMeta.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)) shouldBe "Bearer tok-123"

    val basicMeta = captureMetadata(AuthPreset.BasicAuth("alice", "pw").toHeaders)
    val expectedBasic = Base64.getEncoder.encodeToString("alice:pw".getBytes(StandardCharsets.UTF_8))
    basicMeta.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)) shouldBe s"Basic $expectedBasic"
  }
}
