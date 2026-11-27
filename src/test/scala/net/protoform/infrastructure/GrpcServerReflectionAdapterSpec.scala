package net.protoform.infrastructure

import com.google.protobuf.DescriptorProtos
import io.grpc.{ConnectivityState, Context, ManagedChannel, MethodDescriptor, Server, ServerServiceDefinition}
import io.grpc.netty.shaded.io.grpc.netty.{NettyChannelBuilder, NettyServerBuilder}
import io.grpc.reflection.v1alpha
import io.grpc.stub.{ServerCalls, StreamObserver}
import net.protoform.core.error.DomainError
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.RpcPattern
import net.protoform.infrastructure.reflection.GrpcServerReflectionAdapter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration._

class GrpcServerReflectionAdapterSpec extends AnyFunSuite with Matchers {

  private val v1ReflectionMethod: MethodDescriptor[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse] =
    v1alpha.ServerReflectionGrpc.getServerReflectionInfoMethod()
      .toBuilder()
      .setFullMethodName("grpc.reflection.v1.ServerReflection/ServerReflectionInfo")
      .build()

  private def buildV1ReflectionService(
    handler: StreamObserver[v1alpha.ServerReflectionResponse] => StreamObserver[v1alpha.ServerReflectionRequest]
  ): ServerServiceDefinition = {
    ServerServiceDefinition.builder("grpc.reflection.v1.ServerReflection")
      .addMethod(
        v1ReflectionMethod,
        ServerCalls.asyncBidiStreamingCall(
          new ServerCalls.BidiStreamingMethod[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse] {
            override def invoke(
              responseObserver: StreamObserver[v1alpha.ServerReflectionResponse]
            ): StreamObserver[v1alpha.ServerReflectionRequest] = {
              handler(responseObserver)
            }
          }
        )
      )
      .build()
  }

  private def withServer[A](service: ServerServiceDefinition)(body: (EndpointTarget, ManagedChannel) => A): A = {
    val server: Server = NettyServerBuilder.forPort(0)
      .addService(service)
      .build()
      .start()
    val channel: ManagedChannel = NettyChannelBuilder
      .forAddress("127.0.0.1", server.getPort)
      .usePlaintext()
      .build()
    val target = EndpointTarget(name = s"127.0.0.1:${server.getPort}", host = "127.0.0.1", port = server.getPort)
    try {
      body(target, channel)
    } finally {
      channel.shutdownNow()
      channel.awaitTermination(2, TimeUnit.SECONDS)
      server.shutdownNow()
      server.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  private def buildCommonFileProto(): DescriptorProtos.FileDescriptorProto = {
    val sharedId = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("SharedId")
      .addField(
        DescriptorProtos.FieldDescriptorProto.newBuilder()
          .setName("id_value")
          .setNumber(1)
          .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
          .build()
      )
      .build()

    DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("common.proto")
      .setPackage("acme.common")
      .setSyntax("proto3")
      .addMessageType(sharedId)
      .build()
  }

  private def buildOrdersFileProto(): DescriptorProtos.FileDescriptorProto = {
    val orderReply = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("OrderReply")
      .addField(
        DescriptorProtos.FieldDescriptorProto.newBuilder()
          .setName("order_id")
          .setNumber(1)
          .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
          .setTypeName(".acme.common.SharedId")
          .build()
      )
      .build()

    val orderService = DescriptorProtos.ServiceDescriptorProto.newBuilder()
      .setName("OrderService")
      .addMethod(
        DescriptorProtos.MethodDescriptorProto.newBuilder()
          .setName("GetOrder")
          .setInputType(".acme.common.SharedId")
          .setOutputType(".acme.orders.OrderReply")
          .setClientStreaming(false)
          .setServerStreaming(false)
          .build()
      )
      .addMethod(
        DescriptorProtos.MethodDescriptorProto.newBuilder()
          .setName("WatchOrders")
          .setInputType(".acme.common.SharedId")
          .setOutputType(".acme.orders.OrderReply")
          .setClientStreaming(false)
          .setServerStreaming(true)
          .build()
      )
      .build()

    DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("orders.proto")
      .setPackage("acme.orders")
      .setSyntax("proto3")
      .addDependency("common.proto")
      .addMessageType(orderReply)
      .addService(orderService)
      .build()
  }

  private def buildStandaloneFileProto(): DescriptorProtos.FileDescriptorProto = {
    val pingMsg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("PingRequest")
      .addField(
        DescriptorProtos.FieldDescriptorProto.newBuilder()
          .setName("message")
          .setNumber(1)
          .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
          .build()
      )
      .build()

    val pingService = DescriptorProtos.ServiceDescriptorProto.newBuilder()
      .setName("PingService")
      .addMethod(
        DescriptorProtos.MethodDescriptorProto.newBuilder()
          .setName("Ping")
          .setInputType(".acme.ping.PingRequest")
          .setOutputType(".acme.ping.PingRequest")
          .build()
      )
      .build()

    DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("ping.proto")
      .setPackage("acme.ping")
      .setSyntax("proto3")
      .addMessageType(pingMsg)
      .addService(pingService)
      .build()
  }

  test("discovers services using gRPC reflection v1") {
    val pingProto = buildStandaloneFileProto()

    val v1Service = buildV1ReflectionService { responseObserver =>
      new StreamObserver[v1alpha.ServerReflectionRequest] {
        override def onNext(req: v1alpha.ServerReflectionRequest): Unit = {
          req.getMessageRequestCase match {
            case v1alpha.ServerReflectionRequest.MessageRequestCase.LIST_SERVICES =>
              val listResp = v1alpha.ListServiceResponse.newBuilder()
                .addService(v1alpha.ServiceResponse.newBuilder().setName("grpc.reflection.v1.ServerReflection").build())
                .addService(v1alpha.ServiceResponse.newBuilder().setName("acme.ping.PingService").build())
                .build()
              responseObserver.onNext(
                v1alpha.ServerReflectionResponse.newBuilder()
                  .setOriginalRequest(req)
                  .setListServicesResponse(listResp)
                  .build()
              )
            case v1alpha.ServerReflectionRequest.MessageRequestCase.FILE_CONTAINING_SYMBOL =>
              val fdResp = v1alpha.FileDescriptorResponse.newBuilder()
                .addFileDescriptorProto(pingProto.toByteString)
                .build()
              responseObserver.onNext(
                v1alpha.ServerReflectionResponse.newBuilder()
                  .setOriginalRequest(req)
                  .setFileDescriptorResponse(fdResp)
                  .build()
              )
            case _ => ()
          }
        }
        override def onError(t: Throwable): Unit = ()
        override def onCompleted(): Unit = responseObserver.onCompleted()
      }
    }

    withServer(v1Service) { (target, channel) =>
      val adapter = new GrpcServerReflectionAdapter()
      val result = Await.result(adapter.discoverServices(target, channel), 5.seconds)

      result.isRight shouldBe true
      val services = result.toOption.get
      services.map(_.fullName) shouldBe List("acme.ping.PingService")
      services.head.packageName shouldBe "acme.ping"
      services.head.methods.map(_.name) shouldBe List("Ping")
      services.head.methods.head.pattern shouldBe RpcPattern.Unary
    }
  }

  test("falls back to v1alpha when v1 reflection returns UNIMPLEMENTED") {
    val pingProto = buildStandaloneFileProto()

    val v1AlphaService = new v1alpha.ServerReflectionGrpc.ServerReflectionImplBase {
      override def serverReflectionInfo(
        responseObserver: StreamObserver[v1alpha.ServerReflectionResponse]
      ): StreamObserver[v1alpha.ServerReflectionRequest] = {
        new StreamObserver[v1alpha.ServerReflectionRequest] {
          override def onNext(req: v1alpha.ServerReflectionRequest): Unit = {
            req.getMessageRequestCase match {
              case v1alpha.ServerReflectionRequest.MessageRequestCase.LIST_SERVICES =>
                val listResp = v1alpha.ListServiceResponse.newBuilder()
                  .addService(v1alpha.ServiceResponse.newBuilder().setName("grpc.reflection.v1alpha.ServerReflection").build())
                  .addService(v1alpha.ServiceResponse.newBuilder().setName("acme.ping.PingService").build())
                  .build()
                responseObserver.onNext(
                  v1alpha.ServerReflectionResponse.newBuilder()
                    .setOriginalRequest(req)
                    .setListServicesResponse(listResp)
                    .build()
                )
              case v1alpha.ServerReflectionRequest.MessageRequestCase.FILE_CONTAINING_SYMBOL =>
                val fdResp = v1alpha.FileDescriptorResponse.newBuilder()
                  .addFileDescriptorProto(pingProto.toByteString)
                  .build()
                responseObserver.onNext(
                  v1alpha.ServerReflectionResponse.newBuilder()
                    .setOriginalRequest(req)
                    .setFileDescriptorResponse(fdResp)
                    .build()
                )
              case _ => ()
            }
          }
          override def onError(t: Throwable): Unit = ()
          override def onCompleted(): Unit = responseObserver.onCompleted()
        }
      }
    }

    withServer(v1AlphaService.bindService()) { (target, channel) =>
      val adapter = new GrpcServerReflectionAdapter()
      val result = Await.result(adapter.discoverServices(target, channel), 5.seconds)

      result.isRight shouldBe true
      val services = result.toOption.get
      services.map(_.fullName) shouldBe List("acme.ping.PingService")
      services.head.methods.map(_.name) shouldBe List("Ping")
    }
  }

  test("fetches missing transitive dependencies via file_by_filename") {
    val commonProto = buildCommonFileProto()
    val ordersProto = buildOrdersFileProto()
    @volatile var requestedCommonByFilename = false

    val v1Service = buildV1ReflectionService { responseObserver =>
      new StreamObserver[v1alpha.ServerReflectionRequest] {
        override def onNext(req: v1alpha.ServerReflectionRequest): Unit = {
          req.getMessageRequestCase match {
            case v1alpha.ServerReflectionRequest.MessageRequestCase.LIST_SERVICES =>
              val listResp = v1alpha.ListServiceResponse.newBuilder()
                .addService(v1alpha.ServiceResponse.newBuilder().setName("acme.orders.OrderService").build())
                .build()
              responseObserver.onNext(
                v1alpha.ServerReflectionResponse.newBuilder()
                  .setOriginalRequest(req)
                  .setListServicesResponse(listResp)
                  .build()
              )
            case v1alpha.ServerReflectionRequest.MessageRequestCase.FILE_CONTAINING_SYMBOL =>
              // Intentionally omit common.proto so the client must fetch it via file_by_filename.
              val fdResp = v1alpha.FileDescriptorResponse.newBuilder()
                .addFileDescriptorProto(ordersProto.toByteString)
                .build()
              responseObserver.onNext(
                v1alpha.ServerReflectionResponse.newBuilder()
                  .setOriginalRequest(req)
                  .setFileDescriptorResponse(fdResp)
                  .build()
              )
            case v1alpha.ServerReflectionRequest.MessageRequestCase.FILE_BY_FILENAME =>
              if (req.getFileByFilename == "common.proto") {
                requestedCommonByFilename = true
                val fdResp = v1alpha.FileDescriptorResponse.newBuilder()
                  .addFileDescriptorProto(commonProto.toByteString)
                  .build()
                responseObserver.onNext(
                  v1alpha.ServerReflectionResponse.newBuilder()
                    .setOriginalRequest(req)
                    .setFileDescriptorResponse(fdResp)
                    .build()
                )
              }
            case _ => ()
          }
        }
        override def onError(t: Throwable): Unit = ()
        override def onCompleted(): Unit = responseObserver.onCompleted()
      }
    }

    withServer(v1Service) { (target, channel) =>
      val adapter = new GrpcServerReflectionAdapter()
      val result = Await.result(adapter.discoverServices(target, channel), 5.seconds)

      requestedCommonByFilename shouldBe true
      result.isRight shouldBe true
      val services = result.toOption.get
      services.map(_.fullName) shouldBe List("acme.orders.OrderService")
      val methods = services.head.methods
      methods.map(m => (m.name, m.pattern)) shouldBe List(
        ("GetOrder", RpcPattern.Unary),
        ("WatchOrders", RpcPattern.ServerStreaming)
      )
      methods.head.inputSchema.fields().map(_.name) shouldBe List("id_value")
    }
  }

  test("times out and cancels open bidi stream when server does not respond") {
    val cancelledLatch = new CountDownLatch(1)

    val hangingService = buildV1ReflectionService { _ =>
      Context.current().addListener(
        new Context.CancellationListener {
          override def cancelled(context: Context): Unit = {
            cancelledLatch.countDown()
          }
        },
        new java.util.concurrent.Executor {
          override def execute(command: Runnable): Unit = command.run()
        }
      )
      new StreamObserver[v1alpha.ServerReflectionRequest] {
        override def onNext(req: v1alpha.ServerReflectionRequest): Unit = ()
        override def onError(t: Throwable): Unit = cancelledLatch.countDown()
        override def onCompleted(): Unit = ()
      }
    }

    withServer(hangingService) { (target, channel) =>
      // Ensure HTTP/2 transport is READY before starting the short timeout clock on loaded CI runners.
      val readyLatch = new CountDownLatch(1)
      def awaitReady(): Unit = {
        val state = channel.getState(true)
        if (state == ConnectivityState.READY) {
          readyLatch.countDown()
        } else if (state != ConnectivityState.SHUTDOWN) {
          channel.notifyWhenStateChanged(state, () => awaitReady())
        }
      }
      awaitReady()
      readyLatch.await(3, TimeUnit.SECONDS) shouldBe true

      val adapter = new GrpcServerReflectionAdapter(timeoutMillis = 800L)
      val result = Await.result(adapter.discoverServices(target, channel), 5.seconds)

      result match {
        case Left(DomainError.ReflectionNotSupported(_, detail)) =>
          detail should include("timed out")
        case other =>
          fail(s"Expected timeout ReflectionNotSupported error, got $other")
      }
      cancelledLatch.await(5, TimeUnit.SECONDS) shouldBe true
    }
  }
}
