package net.protoform.infrastructure

import com.google.gson.JsonParser
import com.google.protobuf.{DescriptorProtos, Descriptors, DynamicMessage}
import io.grpc.{ForwardingServerCall, ManagedChannel, Metadata, MethodDescriptor, Server, ServerCall, ServerCallHandler, ServerInterceptor, ServerServiceDefinition, Status}
import io.grpc.netty.shaded.io.grpc.netty.{NettyChannelBuilder, NettyServerBuilder}
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.{ServerCalls, StreamObserver}
import net.protoform.core.auth.MetadataHeader
import net.protoform.core.call.{RpcCall, RpcCallStatus, StreamChunk}
import net.protoform.core.error.DomainError
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.{MethodSchema, ProtoType, RpcPattern, SchemaHandle}
import net.protoform.infrastructure.grpc.DynamicCallAdapter
import net.protoform.infrastructure.schema.DescriptorRegistry
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class DynamicCallAdapterSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val echoDescriptor: Descriptors.Descriptor = {
    val msgProto = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("EchoMessage")
      .addField(
        DescriptorProtos.FieldDescriptorProto.newBuilder()
          .setName("text")
          .setNumber(1)
          .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
          .build()
      )
      .addField(
        DescriptorProtos.FieldDescriptorProto.newBuilder()
          .setName("count")
          .setNumber(2)
          .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
          .build()
      )
      .build()

    val fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("echo.proto")
      .setPackage("test")
      .setSyntax("proto3")
      .addMessageType(msgProto)
      .build()

    Descriptors.FileDescriptor.buildFrom(fileProto, Array.empty[Descriptors.FileDescriptor]).findMessageTypeByName("EchoMessage")
  }

  private val textField = echoDescriptor.findFieldByName("text")
  private val countField = echoDescriptor.findFieldByName("count")

  private def buildEchoMessage(text: String, count: Int): DynamicMessage = {
    DynamicMessage.newBuilder(echoDescriptor)
      .setField(textField, text)
      .setField(countField, Int.box(count))
      .build()
  }

  private def grpcDescriptor(name: String, methodType: MethodDescriptor.MethodType): MethodDescriptor[DynamicMessage, DynamicMessage] = {
    MethodDescriptor.newBuilder[DynamicMessage, DynamicMessage]()
      .setType(methodType)
      .setFullMethodName(MethodDescriptor.generateFullMethodName("test.EchoService", name))
      .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(echoDescriptor)))
      .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(echoDescriptor)))
      .build()
  }

  private val descriptorRegistry = new DescriptorRegistry()
  private val schemaHandle: SchemaHandle = descriptorRegistry.registerDescriptors(Seq(echoDescriptor))

  private def methodSchema(name: String, pattern: RpcPattern): MethodSchema = {
    val msgType = ProtoType.MessageType("test.EchoMessage", () => Nil)
    MethodSchema(
      name = name,
      fullMethodName = s"test.EchoService/$name",
      serviceFullName = "test.EchoService",
      pattern = pattern,
      inputSchema = msgType,
      outputSchema = msgType,
      schemaHandle = schemaHandle
    )
  }

  private var server: Server = null
  private var channel: ManagedChannel = null
  private var target: EndpointTarget = null
  private val adapter = new DynamicCallAdapter(descriptorRegistry)

  override protected def beforeAll(): Unit = {
    val headerTrailerInterceptor = new ServerInterceptor {
      override def interceptCall[ReqT, RespT](
        call: ServerCall[ReqT, RespT],
        headers: Metadata,
        next: ServerCallHandler[ReqT, RespT]
      ): ServerCall.Listener[ReqT] = {
        val wrapped = new ForwardingServerCall.SimpleForwardingServerCall[ReqT, RespT](call) {
          override def sendHeaders(responseHeaders: Metadata): Unit = {
            responseHeaders.put(Metadata.Key.of("x-server-header", Metadata.ASCII_STRING_MARSHALLER), "header-val")
            call.sendHeaders(responseHeaders)
          }

          override def close(status: Status, trailers: Metadata): Unit = {
            trailers.put(Metadata.Key.of("x-server-trailer", Metadata.ASCII_STRING_MARSHALLER), "trailer-val")
            call.close(status, trailers)
          }
        }
        next.startCall(wrapped, headers)
      }
    }

    val serviceDef = ServerServiceDefinition.builder("test.EchoService")
      .addMethod(
        grpcDescriptor("UnaryEcho", MethodDescriptor.MethodType.UNARY),
        ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod[DynamicMessage, DynamicMessage] {
          override def invoke(request: DynamicMessage, responseObserver: StreamObserver[DynamicMessage]): Unit = {
            val text = request.getField(textField).asInstanceOf[String]
            val count = request.getField(countField).asInstanceOf[Int]
            responseObserver.onNext(buildEchoMessage(s"echo:$text", count + 1))
            responseObserver.onCompleted()
          }
        })
      )
      .addMethod(
        grpcDescriptor("FailWithSpecialChars", MethodDescriptor.MethodType.UNARY),
        ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod[DynamicMessage, DynamicMessage] {
          override def invoke(request: DynamicMessage, responseObserver: StreamObserver[DynamicMessage]): Unit = {
            responseObserver.onError(
              Status.INVALID_ARGUMENT
                .withDescription("bad \"input\"\npath\\to\\file")
                .asRuntimeException()
            )
          }
        })
      )
      .addMethod(
        grpcDescriptor("ServerStreamEcho", MethodDescriptor.MethodType.SERVER_STREAMING),
        ServerCalls.asyncServerStreamingCall(new ServerCalls.ServerStreamingMethod[DynamicMessage, DynamicMessage] {
          override def invoke(request: DynamicMessage, responseObserver: StreamObserver[DynamicMessage]): Unit = {
            val text = request.getField(textField).asInstanceOf[String]
            val count = request.getField(countField).asInstanceOf[Int]
            (1 to count).foreach { i =>
              responseObserver.onNext(buildEchoMessage(s"$text-$i", i))
            }
            responseObserver.onCompleted()
          }
        })
      )
      .addMethod(
        grpcDescriptor("ClientStreamSum", MethodDescriptor.MethodType.CLIENT_STREAMING),
        ServerCalls.asyncClientStreamingCall(new ServerCalls.ClientStreamingMethod[DynamicMessage, DynamicMessage] {
          override def invoke(responseObserver: StreamObserver[DynamicMessage]): StreamObserver[DynamicMessage] = {
            new StreamObserver[DynamicMessage] {
              private val texts = scala.collection.mutable.ListBuffer.empty[String]
              private var total = 0

              override def onNext(value: DynamicMessage): Unit = {
                texts += value.getField(textField).asInstanceOf[String]
                total += value.getField(countField).asInstanceOf[Int]
              }

              override def onError(t: Throwable): Unit = ()

              override def onCompleted(): Unit = {
                responseObserver.onNext(buildEchoMessage(texts.mkString(","), total))
                responseObserver.onCompleted()
              }
            }
          }
        })
      )
      .build()

    server = NettyServerBuilder.forPort(0)
      .intercept(headerTrailerInterceptor)
      .addService(serviceDef)
      .build()
      .start()

    target = EndpointTarget(name = "ephemeral", host = "localhost", port = server.getPort, useTls = false)
    channel = NettyChannelBuilder.forAddress("localhost", server.getPort).usePlaintext().build()
  }

  override protected def afterAll(): Unit = {
    if (channel != null) {
      channel.shutdownNow()
      channel.awaitTermination(3, TimeUnit.SECONDS)
    }
    if (server != null) {
      server.shutdownNow()
      server.awaitTermination(3, TimeUnit.SECONDS)
    }
  }

  test("executeUnary returns response body and captures response headers and trailers") {
    val method = methodSchema("UnaryEcho", RpcPattern.Unary)
    val result = Await.result(
      adapter.executeUnary(channel, target, method, """{"text": "hello", "count": 5}""", List(MetadataHeader("x-req", "1"))),
      5.seconds
    )

    result.isRight shouldBe true
    val call = result.toOption.get
    call.status shouldBe a[RpcCallStatus.Completed]
    val bodyObj = JsonParser.parseString(call.responseBodyJson.get).getAsJsonObject
    bodyObj.get("text").getAsString shouldBe "echo:hello"
    bodyObj.get("count").getAsInt shouldBe 6
    call.responseHeaders.get("x-server-header") shouldBe Some("header-val")
    call.responseTrailers.get("x-server-trailer") shouldBe Some("trailer-val")
  }

  test("executeUnary escapes quotes, newlines, and backslashes in JSON error payloads") {
    val method = methodSchema("FailWithSpecialChars", RpcPattern.Unary)
    val result = Await.result(
      adapter.executeUnary(channel, target, method, "{}", Nil),
      5.seconds
    )

    result.isRight shouldBe true
    val call = result.toOption.get
    call.status shouldBe a[RpcCallStatus.Failed]
    val parsed = JsonParser.parseString(call.responseBodyJson.get).getAsJsonObject
    parsed.get("error").getAsString shouldBe "INVALID_ARGUMENT: bad \"input\"\npath\\to\\file"
    call.responseTrailers.get("x-server-trailer") shouldBe Some("trailer-val")
  }

  test("executeServerStreaming emits ordered chunks and completes") {
    val method = methodSchema("ServerStreamEcho", RpcPattern.ServerStreaming)
    val chunks = new CopyOnWriteArrayList[StreamChunk]()
    val statusRef = new AtomicReference[RpcCallStatus]()
    val latch = new CountDownLatch(1)

    adapter.executeServerStreaming(
      channel,
      target,
      method,
      """{"text": "msg", "count": 3}""",
      Nil,
      chunk => chunks.add(chunk),
      status => {
        statusRef.set(status)
        latch.countDown()
      }
    )

    latch.await(5, TimeUnit.SECONDS) shouldBe true
    statusRef.get() shouldBe a[RpcCallStatus.Completed]
    val collected = chunks.asScala.toList
    collected should have size 3
    collected.map(_.index) shouldBe List(1L, 2L, 3L)
    collected.map(c => JsonParser.parseString(c.payloadJson).getAsJsonObject.get("text").getAsString) shouldBe List("msg-1", "msg-2", "msg-3")
  }

  test("executeClientStreaming aggregates messages, guards closed stream, and captures headers and trailers") {
    val method = methodSchema("ClientStreamSum", RpcPattern.ClientStreaming)
    val resultRef = new AtomicReference[Either[DomainError, RpcCall]]()
    val latch = new CountDownLatch(1)

    val stream = adapter.executeClientStreaming(
      channel,
      target,
      method,
      Nil,
      res => {
        resultRef.set(res)
        latch.countDown()
      }
    )

    stream.sendNext("""{"text": "a", "count": 2}""").isRight shouldBe true
    stream.sendNext("""{"text": "b", "count": 3}""").isRight shouldBe true
    stream.complete()
    stream.sendNext("""{"text": "after-close", "count": 1}""").isLeft shouldBe true

    latch.await(5, TimeUnit.SECONDS) shouldBe true
    val call = resultRef.get().toOption.get
    call.status shouldBe a[RpcCallStatus.Completed]
    val bodyObj = JsonParser.parseString(call.responseBodyJson.get).getAsJsonObject
    bodyObj.get("text").getAsString shouldBe "a,b"
    bodyObj.get("count").getAsInt shouldBe 5
    call.responseHeaders.get("x-server-header") shouldBe Some("header-val")
    call.responseTrailers.get("x-server-trailer") shouldBe Some("trailer-val")
  }
}
