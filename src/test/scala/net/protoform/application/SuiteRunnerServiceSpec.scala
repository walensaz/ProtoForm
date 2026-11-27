package net.protoform.application

import com.google.protobuf.{DescriptorProtos, Descriptors, DynamicMessage}
import io.grpc.{ManagedChannel, MethodDescriptor, Server, ServerServiceDefinition, Status}
import io.grpc.netty.shaded.io.grpc.netty.{NettyChannelBuilder, NettyServerBuilder}
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.{ServerCalls, StreamObserver}
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.{MethodSchema, ProtoType, RpcPattern, ServiceSchema}
import net.protoform.core.workspace.ServiceTemplate
import net.protoform.infrastructure.grpc.DynamicCallAdapter
import net.protoform.infrastructure.schema.DescriptorRegistry
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class SuiteRunnerServiceSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val echoDescriptor: Descriptors.Descriptor = {
    val msgProto = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("SuiteMsg")
      .addField(
        DescriptorProtos.FieldDescriptorProto.newBuilder()
          .setName("value")
          .setNumber(1)
          .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
      )
      .build()
    val fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("suite.proto")
      .setPackage("qa")
      .setSyntax("proto3")
      .addMessageType(msgProto)
      .build()
    Descriptors.FileDescriptor.buildFrom(fileProto, Array.empty[Descriptors.FileDescriptor]).findMessageTypeByName("SuiteMsg")
  }

  private val valueField = echoDescriptor.findFieldByName("value")
  private val registry = new DescriptorRegistry()
  private val handle = registry.registerDescriptors(Seq(echoDescriptor))
  private val invocationService = new InvocationService(new DynamicCallAdapter(registry))
  private val suiteRunner = new SuiteRunnerService(invocationService)

  private var server: Server = _
  private var channel: ManagedChannel = _
  private var target: EndpointTarget = _

  override protected def beforeAll(): Unit = {
    val grpcMethod = MethodDescriptor.newBuilder[DynamicMessage, DynamicMessage]()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName("qa.CheckService/Validate")
      .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(echoDescriptor)))
      .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(echoDescriptor)))
      .build()

    val svcDef = ServerServiceDefinition.builder("qa.CheckService")
      .addMethod(
        grpcMethod,
        ServerCalls.asyncUnaryCall(new ServerCalls.UnaryMethod[DynamicMessage, DynamicMessage] {
          override def invoke(req: DynamicMessage, resp: StreamObserver[DynamicMessage]): Unit = {
            val v = req.getField(valueField).asInstanceOf[String]
            if (v == "bad") {
              resp.onError(Status.INVALID_ARGUMENT.withDescription("rejected").asRuntimeException())
            } else {
              resp.onNext(DynamicMessage.newBuilder(echoDescriptor).setField(valueField, s"ok:$v").build())
              resp.onCompleted()
            }
          }
        })
      )
      .build()

    server = NettyServerBuilder.forPort(0).addService(svcDef).build().start()
    channel = NettyChannelBuilder.forAddress("localhost", server.getPort).usePlaintext().build()
    target = EndpointTarget(name = "qa-ephemeral", host = "localhost", port = server.getPort)
  }

  override protected def afterAll(): Unit = {
    if (channel != null) { channel.shutdownNow(); channel.awaitTermination(2, TimeUnit.SECONDS) }
    if (server != null) { server.shutdownNow(); server.awaitTermination(2, TimeUnit.SECONDS) }
  }

  test("runSuite executes templates with {{var}} interpolation, verifies expectedStatus, and exports reports") {
    val msgType = ProtoType.MessageType("qa.SuiteMsg", () => Nil)
    val method = MethodSchema("Validate", "qa.CheckService/Validate", "qa.CheckService", RpcPattern.Unary, msgType, msgType, handle)
    val service = ServiceSchema("qa.CheckService", "qa", "CheckService", List(method), handle)

    val tplPassOk = ServiceTemplate("t1", "Valid Input", serviceFullName = "qa.CheckService", methodName = "Validate", requestJson = """{"value":"{{item}}"}""", expectedStatus = Some("OK"))
    val tplPassNegative = ServiceTemplate("t2", "Invalid Input", serviceFullName = "qa.CheckService", methodName = "Validate", requestJson = """{"value":"bad"}""", expectedStatus = Some("INVALID_ARGUMENT"))
    val tplFailMismatch = ServiceTemplate("t3", "Mismatch", serviceFullName = "qa.CheckService", methodName = "Validate", requestJson = """{"value":"bad"}""", expectedStatus = Some("OK"))

    val summary = Await.result(
      suiteRunner.runSuite(
        templates = List(tplPassOk, tplPassNegative, tplFailMismatch),
        services = List(service),
        channel = channel,
        target = target,
        variables = Map("item" -> "alpha")
      ),
      5.seconds
    )

    summary.passedCount shouldBe 2
    summary.failedCount shouldBe 1
    summary.results.head.requestJsonResolved shouldBe """{"value":"alpha"}"""
    summary.results(1).actualStatusName shouldBe "INVALID_ARGUMENT"
    summary.results(2).passed shouldBe false

    val md = SuiteRunnerService.exportReportMarkdown(summary)
    md should include("Valid Input")
    md should include("PASS")
    md should include("FAIL")

    val json = SuiteRunnerService.exportReportJson(summary)
    json should include("\"passed\": 2")
    json should include("\"failed\": 1")
  }
}
