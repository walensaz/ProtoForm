package net.protoform.core

import net.protoform.core.auth.MetadataHeader
import net.protoform.core.call._
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.{MethodSchema, ProtoType, RpcPattern, ServiceSchema}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PythonSnippetGeneratorSpec extends AnyFunSuite with Matchers {

  private val sampleMethod = MethodSchema(
    name = "CreateOrder",
    fullMethodName = "demo.orders.v1.OrderService/CreateOrder",
    serviceFullName = "demo.orders.v1.OrderService",
    pattern = RpcPattern.Unary,
    inputSchema = ProtoType.MessageType("demo.orders.v1.CreateOrderRequest", () => Nil),
    outputSchema = ProtoType.MessageType("demo.orders.v1.CreateOrderResponse", () => Nil)
  )

  private val sampleService = ServiceSchema(
    fullName = "demo.orders.v1.OrderService",
    packageName = "demo.orders.v1",
    simpleName = "OrderService",
    methods = List(sampleMethod),
    protoFile = Some("demo/orders.proto")
  )

  test("inferStubModules derives package and pb2 names from protoFile") {
    val (pb2, grpc, pkg) = PythonSnippetGenerator.inferStubModules(Some("demo/orders.proto"), "demo.orders.v1.OrderService")
    pb2 shouldBe "orders_pb2"
    grpc shouldBe "orders_pb2_grpc"
    pkg shouldBe Some("demo")
  }

  test("generates stub-based Python unary snippet with TLS, metadata, options, and Python literals") {
    val target = EndpointTarget(
      name = "staging",
      host = "api.example.com",
      port = 443,
      useTls = true,
      tlsCertPath = Some("/certs/ca.pem"),
      overrideAuthority = Some("orders.internal")
    )
    val headers = List(
      MetadataHeader("authorization", "Bearer secret-123", enabled = true),
      MetadataHeader("trace-bin", "AQID", enabled = true),
      MetadataHeader("x-disabled", "skip", enabled = false)
    )
    val json = """{"order_id": "ord-1", "urgent": true, "note": null, "qty": 3}"""
    val opts = RpcCallOptions(deadlineSeconds = 15, useGzipCompression = true, preserveProtoFieldNames = true)

    val code = PythonSnippetGenerator.generate(
      target = target,
      method = sampleMethod,
      service = Some(sampleService),
      requestJson = json,
      headers = headers,
      callOptions = opts,
      config = PythonExportConfig(mode = PythonExportMode.Stubs)
    )

    code should include("import base64")
    code should include("from pathlib import Path")
    code should include("from demo import orders_pb2, orders_pb2_grpc")
    code should include("grpc.ssl_channel_credentials(")
    code should include("""root_certificates=Path("/certs/ca.pem").read_bytes()""")
    code should include("""("grpc.ssl_target_name_override", "orders.internal")""")
    code should include("""("authorization", "Bearer secret-123")""")
    code should include("""("trace-bin", base64.b64decode("AQID"))""")
    code should not include "x-disabled"
    code should include(""""urgent": True""")
    code should include(""""note": None""")
    code should include("stub = orders_pb2_grpc.OrderServiceStub(channel)")
    code should include("timeout=15")
    code should include("compression=grpc.Compression.Gzip")
  }

  test("generates dynamic reflection Python client for bidirectional streaming with scripted chunks") {
    val bidiMethod = sampleMethod.copy(
      name = "InteractiveChat",
      fullMethodName = "demo.sandbox.v1.SandboxService/InteractiveChat",
      serviceFullName = "demo.sandbox.v1.SandboxService",
      pattern = RpcPattern.BidirectionalStreaming
    )
    val target = EndpointTarget(name = "local", host = "::1", port = 50051, useTls = false)

    val code = PythonSnippetGenerator.generate(
      target = target,
      method = bidiMethod,
      service = None,
      requestJson = """{"text": "fallback"}""",
      headers = Nil,
      callOptions = RpcCallOptions(),
      config = PythonExportConfig(
        mode = PythonExportMode.Reflection,
        streamPayloads = List("""{"text": "hello"}""", """{"text": "world"}""")
      )
    )

    code should include("ProtoReflectionDescriptorDatabase(channel)")
    code should include("GetMessageClass(method_desc.input_type)")
    code should include("""grpc.insecure_channel("[::1]:50051")""")
    code should include("channel.stream_stream(")
    code should include("def request_stream():")
    code should include(""""text": "hello"""")
    code should include(""""text": "world"""")
    code should not include "fallback"
  }

  test("GrpcurlFormatter formats plaintext IPv6 and TLS authority flags") {
    val target = EndpointTarget(name = "v6", host = "::1", port = 50051, useTls = false)
    val cmd = GrpcurlFormatter.format(
      target = target,
      method = sampleMethod,
      resolvedPayload = """{"id": "1"}""",
      resolvedHeaders = List(MetadataHeader("x-env", "dev", enabled = true))
    )
    cmd should include("-plaintext")
    cmd should include("""-H "x-env: dev"""")
    cmd should include("[::1]:50051")
    cmd should include("demo.orders.v1.OrderService/CreateOrder")
  }
}
