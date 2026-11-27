package net.protoform.infrastructure

import com.google.protobuf.DescriptorProtos
import net.protoform.core.error.DomainError
import net.protoform.core.schema.RpcPattern
import net.protoform.infrastructure.schema.DescriptorSetSchemaAdapter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class DescriptorSetSchemaAdapterSpec extends AnyFunSuite with Matchers {

  private val adapter = new DescriptorSetSchemaAdapter()

  test("loadDescriptorSet parses FileDescriptorSet with transitive message dependencies") {
    val commonProto = DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("common.proto")
      .setPackage("demo.common")
      .setSyntax("proto3")
      .addMessageType(
        DescriptorProtos.DescriptorProto.newBuilder()
          .setName("PingRequest")
          .addField(
            DescriptorProtos.FieldDescriptorProto.newBuilder()
              .setName("message")
              .setNumber(1)
              .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
              .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          )
      )
      .addMessageType(
        DescriptorProtos.DescriptorProto.newBuilder()
          .setName("PingReply")
          .addField(
            DescriptorProtos.FieldDescriptorProto.newBuilder()
              .setName("reply")
              .setNumber(1)
              .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
              .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          )
      )
      .build()

    val svcProto = DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("echo.proto")
      .setPackage("demo.echo")
      .setSyntax("proto3")
      .addDependency("common.proto")
      .addService(
        DescriptorProtos.ServiceDescriptorProto.newBuilder()
          .setName("EchoService")
          .addMethod(
            DescriptorProtos.MethodDescriptorProto.newBuilder()
              .setName("Ping")
              .setInputType(".demo.common.PingRequest")
              .setOutputType(".demo.common.PingReply")
          )
      )
      .build()

    val fdSet = DescriptorProtos.FileDescriptorSet.newBuilder()
      .addFile(commonProto)
      .addFile(svcProto)
      .build()

    val tempFile = Files.createTempFile("protoform-desc-", ".desc")
    try {
      Files.write(tempFile, fdSet.toByteArray)
      val services = adapter.loadDescriptorSet(tempFile).fold(e => fail(e.message), identity)
      services should have size 1
      services.head.fullName shouldBe "demo.echo.EchoService"
      services.head.methods should have size 1
      services.head.methods.head.name shouldBe "Ping"
      services.head.methods.head.pattern shouldBe RpcPattern.Unary
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("loadDescriptorSet returns SchemaParseError when file is missing or invalid") {
    val missing = Files.createTempDirectory("protoform-missing-").resolve("nope.desc")
    adapter.loadDescriptorSet(missing) should matchPattern { case Left(_: DomainError.SchemaParseError) => }
  }
}
