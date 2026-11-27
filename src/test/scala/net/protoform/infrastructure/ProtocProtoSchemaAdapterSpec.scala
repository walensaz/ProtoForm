package net.protoform.infrastructure

import com.google.protobuf.DescriptorProtos
import net.protoform.core.error.DomainError
import net.protoform.core.schema.RpcPattern
import net.protoform.infrastructure.schema.{DescriptorRegistry, DescriptorSetSchemaAdapter, ProtocProtoSchemaAdapter}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

class ProtocProtoSchemaAdapterSpec extends AnyFunSuite with Matchers {

  test("validates empty or missing proto files and include dirs") {
    val adapter = new ProtocProtoSchemaAdapter()
    adapter.loadProtoFiles(Nil, Nil) should matchPattern { case Left(_: DomainError.SchemaParseError) => }
    adapter.loadProtoFiles(List(Paths.get("does-not-exist-protoform.proto")), Nil) should matchPattern {
      case Left(_: DomainError.SchemaParseError) =>
    }
  }

  test("invokes protoc with --descriptor_set_out, --include_imports, and -I flags and loads services") {
    val tempDir = Files.createTempDirectory("protoform-proto-test-")
    try {
      val includeRoot = Files.createDirectories(tempDir.resolve("protos"))
      val protoFile = includeRoot.resolve("greeter.proto")
      Files.writeString(protoFile, "syntax = \"proto3\"; package demo; service Greeter { rpc Hi (Req) returns (Req); } message Req { string name = 1; }")

      val reqMsg = DescriptorProtos.DescriptorProto.newBuilder()
        .setName("Req")
        .addField(
          DescriptorProtos.FieldDescriptorProto.newBuilder()
            .setName("name")
            .setNumber(1)
            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
            .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
        )
        .build()

      val svcProto = DescriptorProtos.FileDescriptorProto.newBuilder()
        .setName("greeter.proto")
        .setPackage("demo")
        .setSyntax("proto3")
        .addMessageType(reqMsg)
        .addService(
          DescriptorProtos.ServiceDescriptorProto.newBuilder()
            .setName("Greeter")
            .addMethod(
              DescriptorProtos.MethodDescriptorProto.newBuilder()
                .setName("Hi")
                .setInputType(".demo.Req")
                .setOutputType(".demo.Req")
            )
        )
        .build()

      val fdSet = DescriptorProtos.FileDescriptorSet.newBuilder().addFile(svcProto).build()

      var capturedCmd: Seq[String] = Nil
      val registry = new DescriptorRegistry()
      val adapter = new ProtocProtoSchemaAdapter(
        descriptorSetAdapter = new DescriptorSetSchemaAdapter(registry),
        commandRunner = { cmd =>
          capturedCmd = cmd
          val outFlag = cmd.find(_.startsWith("--descriptor_set_out=")).get
          val outPath = Paths.get(outFlag.stripPrefix("--descriptor_set_out="))
          Files.write(outPath, fdSet.toByteArray)
          Right((0, ""))
        }
      )

      val services = adapter.loadProtoFiles(List(protoFile), List(includeRoot), Some("/usr/bin/protoc")).fold(
        err => fail(err.message),
        identity
      )

      capturedCmd.headOption shouldBe Some("/usr/bin/protoc")
      capturedCmd should contain("--include_imports")
      capturedCmd should contain(s"-I${includeRoot.toAbsolutePath.normalize()}")
      services.map(_.fullName) shouldBe List("demo.Greeter")
      services.head.methods.head.pattern shouldBe RpcPattern.Unary
      registry.resolveMessage(services.head.methods.head.schemaHandle, "demo.Req").isDefined shouldBe true
    } finally {
      Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
    }
  }

  test("returns SchemaParseError when protoc exits non-zero") {
    val tempFile = Files.createTempFile("protoform-bad-", ".proto")
    try {
      val adapter = new ProtocProtoSchemaAdapter(
        commandRunner = _ => Right((1, "syntax error at line 1"))
      )
      val res = adapter.loadProtoFiles(List(tempFile), Nil)
      res match {
        case Left(DomainError.SchemaParseError(_, detail)) => detail should include("syntax error")
        case other => fail(s"Expected SchemaParseError, got $other")
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
