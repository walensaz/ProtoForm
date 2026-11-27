package net.protoform.demo

import com.google.protobuf.{ByteString, DescriptorProtos, Descriptors, DynamicMessage}
import io.grpc.{CallOptions, ManagedChannel, Metadata, MethodDescriptor, Status, StatusRuntimeException}
import io.grpc.health.v1.{HealthCheckRequest, HealthCheckResponse, HealthGrpc}
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.reflection.v1alpha.{ServerReflectionGrpc, ServerReflectionRequest, ServerReflectionResponse}
import io.grpc.stub.{ClientCalls, MetadataUtils, StreamObserver}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

class DemoServerSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private var server: DemoGrpcServer = null
  private var channel: ManagedChannel = null

  private val sandboxDesc = DemoProtoSchema.sandboxFd.findServiceByName("SandboxService")
  private val ordersDesc = DemoProtoSchema.ordersFd.findServiceByName("OrderService")

  override protected def beforeAll(): Unit = {
    server = new DemoGrpcServer(DemoServerConfig(host = "127.0.0.1", port = 0)).start()
    channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
  }

  override protected def afterAll(): Unit = {
    if (channel != null) {
      channel.shutdownNow()
      channel.awaitTermination(2, TimeUnit.SECONDS)
    }
    if (server != null) {
      server.shutdown()
    }
  }

  private def unaryCall(
    svc: Descriptors.ServiceDescriptor,
    methodName: String,
    req: DynamicMessage,
    headers: Metadata = new Metadata()
  ): DynamicMessage = {
    val md = GrpcServerHelpers.methodDescriptor(svc, methodName)
    val attachedChannel = io.grpc.ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
    ClientCalls.blockingUnaryCall(attachedChannel, md, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), req)
  }

  private def listServicesViaReflection(useV1: Boolean): List[String] = {
    val baseMethod = ServerReflectionGrpc.getServerReflectionInfoMethod()
    val method = if (useV1) {
      baseMethod.toBuilder().setFullMethodName("grpc.reflection.v1.ServerReflection/ServerReflectionInfo").build()
    } else {
      baseMethod
    }

    val services = new CopyOnWriteArrayList[String]()
    val errorRef = new AtomicReference[Throwable]()
    val latch = new CountDownLatch(1)

    val call = channel.newCall(method, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS))
    val reqObs = ClientCalls.asyncBidiStreamingCall(
      call,
      new StreamObserver[ServerReflectionResponse] {
        override def onNext(resp: ServerReflectionResponse): Unit = {
          if (resp.hasListServicesResponse) {
            resp.getListServicesResponse.getServiceList.asScala.foreach(s => services.add(s.getName))
          }
        }
        override def onError(t: Throwable): Unit = { errorRef.set(t); latch.countDown() }
        override def onCompleted(): Unit = latch.countDown()
      }
    )

    reqObs.onNext(ServerReflectionRequest.newBuilder().setListServices("").build())
    reqObs.onCompleted()
    latch.await(5, TimeUnit.SECONDS) shouldBe true
    Option(errorRef.get()).foreach(throw _)
    services.asScala.toList
  }

  test("server reflection lists SandboxService and OrderService on both v1 and v1alpha") {
    val v1AlphaServices = listServicesViaReflection(useV1 = false)
    v1AlphaServices should contain("demo.sandbox.v1.SandboxService")
    v1AlphaServices should contain("demo.orders.v1.OrderService")
    v1AlphaServices should contain("grpc.health.v1.Health")

    val v1Services = listServicesViaReflection(useV1 = true)
    v1Services should contain("demo.sandbox.v1.SandboxService")
    v1Services should contain("demo.orders.v1.OrderService")
  }

  test("server reflection resolves file_containing_symbol with transitive imports") {
    val method = ServerReflectionGrpc.getServerReflectionInfoMethod()
    val files = new CopyOnWriteArrayList[String]()
    val latch = new CountDownLatch(1)
    val reqObs = ClientCalls.asyncBidiStreamingCall(
      channel.newCall(method, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS)),
      new StreamObserver[ServerReflectionResponse] {
        override def onNext(resp: ServerReflectionResponse): Unit = {
          if (resp.hasFileDescriptorResponse) {
            resp.getFileDescriptorResponse.getFileDescriptorProtoList.asScala.foreach { bytes =>
              files.add(DescriptorProtos.FileDescriptorProto.parseFrom(bytes).getName)
            }
          }
        }
        override def onError(t: Throwable): Unit = latch.countDown()
        override def onCompleted(): Unit = latch.countDown()
      }
    )
    reqObs.onNext(ServerReflectionRequest.newBuilder().setFileContainingSymbol("demo.sandbox.v1.SandboxService").build())
    reqObs.onCompleted()
    latch.await(5, TimeUnit.SECONDS) shouldBe true
    files.asScala.toList should contain("demo/sandbox.proto")
  }

  test("health service reports SERVING for root and domain services") {
    val stub = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
    stub.check(HealthCheckRequest.newBuilder().setService("").build()).getStatus shouldBe HealthCheckResponse.ServingStatus.SERVING
    stub.check(HealthCheckRequest.newBuilder().setService("demo.sandbox.v1.SandboxService").build()).getStatus shouldBe HealthCheckResponse.ServingStatus.SERVING
    stub.check(HealthCheckRequest.newBuilder().setService("demo.orders.v1.OrderService").build()).getStatus shouldBe HealthCheckResponse.ServingStatus.SERVING
  }

  test("SandboxService.Echo echoes fields, oneof, bytes, and incoming headers") {
    val reqDesc = DemoProtoSchema.sandboxFd.findMessageTypeByName("EchoRequest")
    val req = DynamicMessage.newBuilder(reqDesc)
      .setField(reqDesc.findFieldByName("message"), "hello protoform")
      .setField(reqDesc.findFieldByName("count"), Int.box(7))
      .setField(reqDesc.findFieldByName("active"), java.lang.Boolean.TRUE)
      .setField(reqDesc.findFieldByName("raw_payload"), ByteString.copyFrom("abc", StandardCharsets.UTF_8))
      .setField(reqDesc.findFieldByName("note"), "oneof-note")
      .build()

    val md = new Metadata()
    md.put(Metadata.Key.of("x-custom-trace", Metadata.ASCII_STRING_MARSHALLER), "trace-123")

    val resp = unaryCall(sandboxDesc, "Echo", req, md)
    val respDesc = resp.getDescriptorForType
    resp.getField(respDesc.findFieldByName("message")).asInstanceOf[String] shouldBe "hello protoform"
    resp.getField(respDesc.findFieldByName("count")).asInstanceOf[Int] shouldBe 7
    resp.getField(respDesc.findFieldByName("raw_payload_bytes")).asInstanceOf[Int] shouldBe 3
    resp.getField(respDesc.findFieldByName("detail_summary")).asInstanceOf[String] shouldBe "note=oneof-note"

    val headersCount = resp.getRepeatedFieldCount(respDesc.findFieldByName("received_headers"))
    headersCount should be >= 1
  }

  test("SandboxService.SimulateBehavior returns requested status code") {
    val reqDesc = DemoProtoSchema.sandboxFd.findMessageTypeByName("BehaviorRequest")
    val statusEnum = DemoProtoSchema.sandboxFd.findEnumTypeByName("GrpcStatusCode")
    val req = DynamicMessage.newBuilder(reqDesc)
      .setField(reqDesc.findFieldByName("status_code"), statusEnum.findValueByName("GRPC_STATUS_CODE_NOT_FOUND"))
      .setField(reqDesc.findFieldByName("error_message"), "custom missing item")
      .build()

    val ex = intercept[StatusRuntimeException] {
      unaryCall(sandboxDesc, "SimulateBehavior", req)
    }
    ex.getStatus.getCode shouldBe Status.Code.NOT_FOUND
    ex.getStatus.getDescription should include("custom missing item")
  }

  test("SandboxService.StreamEvents emits requested number of events") {
    val reqDesc = DemoProtoSchema.sandboxFd.findMessageTypeByName("StreamEventsRequest")
    val req = DynamicMessage.newBuilder(reqDesc)
      .setField(reqDesc.findFieldByName("topic"), "orders.events")
      .setField(reqDesc.findFieldByName("count"), Int.box(3))
      .setField(reqDesc.findFieldByName("interval_ms"), Int.box(10))
      .build()

    val md = GrpcServerHelpers.methodDescriptor(sandboxDesc, "StreamEvents")
    val events = ClientCalls.blockingServerStreamingCall(channel, md, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), req).asScala.toList
    events should have size 3
    val eventDesc = events.head.getDescriptorForType
    events.map(_.getField(eventDesc.findFieldByName("sequence"))) shouldBe List(1, 2, 3)
  }

  test("SandboxService.CollectMetrics aggregates client stream") {
    val md = GrpcServerHelpers.methodDescriptor(sandboxDesc, "CollectMetrics")
    val pointDesc = DemoProtoSchema.sandboxFd.findMessageTypeByName("MetricPoint")
    val summaryRef = new AtomicReference[DynamicMessage]()
    val errorRef = new AtomicReference[Throwable]()
    val latch = new CountDownLatch(1)

    val reqObs = ClientCalls.asyncClientStreamingCall(
      channel.newCall(md, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS)),
      new StreamObserver[DynamicMessage] {
        override def onNext(v: DynamicMessage): Unit = summaryRef.set(v)
        override def onError(t: Throwable): Unit = { errorRef.set(t); latch.countDown() }
        override def onCompleted(): Unit = latch.countDown()
      }
    )

    Seq("cpu" -> 10.0, "mem" -> 20.0, "cpu" -> 30.0).foreach { case (name, value) =>
      reqObs.onNext(
        DynamicMessage.newBuilder(pointDesc)
          .setField(pointDesc.findFieldByName("metric_name"), name)
          .setField(pointDesc.findFieldByName("value"), Double.box(value))
          .build()
      )
    }
    reqObs.onCompleted()
    latch.await(5, TimeUnit.SECONDS) shouldBe true
    Option(errorRef.get()).foreach(throw _)

    val summary = summaryRef.get()
    val d = summary.getDescriptorForType
    summary.getField(d.findFieldByName("count")).asInstanceOf[Int] shouldBe 3
    summary.getField(d.findFieldByName("sum")).asInstanceOf[Double] shouldBe 60.0
    summary.getField(d.findFieldByName("min")).asInstanceOf[Double] shouldBe 10.0
    summary.getField(d.findFieldByName("max")).asInstanceOf[Double] shouldBe 30.0
    summary.getField(d.findFieldByName("average")).asInstanceOf[Double] shouldBe 20.0
  }

  test("SandboxService.InteractiveChat handles bidi commands") {
    val md = GrpcServerHelpers.methodDescriptor(sandboxDesc, "InteractiveChat")
    val chatDesc = DemoProtoSchema.sandboxFd.findMessageTypeByName("ChatMessage")
    val replies = new CopyOnWriteArrayList[String]()
    val errorRef = new AtomicReference[Throwable]()
    val latch = new CountDownLatch(1)

    val reqObs = ClientCalls.asyncBidiStreamingCall(
      channel.newCall(md, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS)),
      new StreamObserver[DynamicMessage] {
        override def onNext(v: DynamicMessage): Unit = {
          replies.add(v.getField(chatDesc.findFieldByName("body")).asInstanceOf[String])
        }
        override def onError(t: Throwable): Unit = { errorRef.set(t); latch.countDown() }
        override def onCompleted(): Unit = latch.countDown()
      }
    )

    reqObs.onNext(DynamicMessage.newBuilder(chatDesc).setField(chatDesc.findFieldByName("body"), "/ping").build())
    reqObs.onNext(DynamicMessage.newBuilder(chatDesc).setField(chatDesc.findFieldByName("body"), "hello").build())
    reqObs.onCompleted()
    latch.await(5, TimeUnit.SECONDS) shouldBe true
    Option(errorRef.get()).foreach(throw _)

    replies.asScala.toList shouldBe List("pong", "[client (neutral)] echoed (#2): hello")
  }

  test("OrderService supports seeded lookup, creation, and Bearer/API-key auth") {
    val getReqDesc = DemoProtoSchema.ordersFd.findMessageTypeByName("GetOrderRequest")
    val seeded = unaryCall(
      ordersDesc,
      "GetOrder",
      DynamicMessage.newBuilder(getReqDesc).setField(getReqDesc.findFieldByName("order_id"), "ord-1001").build()
    )
    seeded.getField(seeded.getDescriptorForType.findFieldByName("customer_id")) shouldBe "cust-acme"

    val auditReqDesc = DemoProtoSchema.ordersFd.findMessageTypeByName("AdminAuditRequest")
    val auditReq = DynamicMessage.newBuilder(auditReqDesc)
      .setField(auditReqDesc.findFieldByName("include_order_ids"), java.lang.Boolean.TRUE)
      .build()

    val unauthEx = intercept[StatusRuntimeException] {
      unaryCall(ordersDesc, "GetAdminAudit", auditReq)
    }
    unauthEx.getStatus.getCode shouldBe Status.Code.UNAUTHENTICATED

    val authMeta = new Metadata()
    authMeta.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), s"Bearer ${OrderServiceImpl.DemoBearerToken}")
    val auditResp = unaryCall(ordersDesc, "GetAdminAudit", auditReq, authMeta)
    auditResp.getField(auditResp.getDescriptorForType.findFieldByName("authenticated_as")).asInstanceOf[String] shouldBe "bearer-admin"
    auditResp.getField(auditResp.getDescriptorForType.findFieldByName("total_orders")).asInstanceOf[Int] should be >= 2

    val createReqDesc = DemoProtoSchema.ordersFd.findMessageTypeByName("CreateOrderRequest")
    val itemDesc = DemoProtoSchema.ordersFd.findMessageTypeByName("OrderItem")
    val moneyDesc = DemoProtoSchema.commonFd.findMessageTypeByName("Money")
    val unitPrice = DynamicMessage.newBuilder(moneyDesc)
      .setField(moneyDesc.findFieldByName("currency"), "USD")
      .setField(moneyDesc.findFieldByName("units"), Long.box(15L))
      .build()
    val item = DynamicMessage.newBuilder(itemDesc)
      .setField(itemDesc.findFieldByName("sku"), "SKU-TEST")
      .setField(itemDesc.findFieldByName("name"), "Test Widget")
      .setField(itemDesc.findFieldByName("quantity"), Int.box(2))
      .setField(itemDesc.findFieldByName("unit_price"), unitPrice)
      .build()
    val createReq = DynamicMessage.newBuilder(createReqDesc)
      .setField(createReqDesc.findFieldByName("customer_id"), "cust-test")
      .addRepeatedField(createReqDesc.findFieldByName("items"), item)
      .build()
    val created = unaryCall(ordersDesc, "CreateOrder", createReq)
    val newOrderId = created.getField(created.getDescriptorForType.findFieldByName("order_id")).asInstanceOf[String]
    newOrderId should startWith("ord-")

    val watchReqDesc = DemoProtoSchema.ordersFd.findMessageTypeByName("WatchOrderRequest")
    val watchReq = DynamicMessage.newBuilder(watchReqDesc)
      .setField(watchReqDesc.findFieldByName("order_id"), newOrderId)
      .setField(watchReqDesc.findFieldByName("step_delay_ms"), Int.box(10))
      .build()
    val updates = ClientCalls.blockingServerStreamingCall(
      channel,
      GrpcServerHelpers.methodDescriptor(ordersDesc, "WatchOrderUpdates"),
      CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
      watchReq
    ).asScala.toList
    updates should have size 5
  }

  test("DemoProtoSchema writes valid FileDescriptorSet bytes") {
    val tmp = Files.createTempFile("protoform-demo-", ".desc")
    try {
      DemoProtoSchema.writeFileDescriptorSet(tmp)
      val parsed = DescriptorProtos.FileDescriptorSet.parseFrom(Files.readAllBytes(tmp))
      parsed.getFileList.asScala.map(_.getName) should contain allOf("demo/common.proto", "demo/sandbox.proto", "demo/orders.proto")
    } finally {
      Files.deleteIfExists(tmp)
    }
  }
}
