package net.protoform.infrastructure

import com.google.protobuf.{AnyProto, DescriptorProtos, Descriptors}
import net.protoform.core.schema.SchemaHandle
import net.protoform.infrastructure.schema.DescriptorRegistry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DescriptorRegistrySpec extends AnyFunSuite with Matchers {

  test("registers nested messages and transitive dependencies, builds TypeRegistry, and releases handle") {
    val outerMsg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("Outer")
      .addNestedType(
        DescriptorProtos.DescriptorProto.newBuilder()
          .setName("Inner")
          .addField(
            DescriptorProtos.FieldDescriptorProto.newBuilder()
              .setName("id")
              .setNumber(1)
              .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
              .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
          )
      )
      .addField(
        DescriptorProtos.FieldDescriptorProto.newBuilder()
          .setName("detail")
          .setNumber(1)
          .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
          .setTypeName(".google.protobuf.Any")
          .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
      )
      .build()

    val fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName("nested.proto")
      .setPackage("acme.nested")
      .setSyntax("proto3")
      .addDependency("google/protobuf/any.proto")
      .addMessageType(outerMsg)
      .build()

    val fd = Descriptors.FileDescriptor.buildFrom(fileProto, Array(AnyProto.getDescriptor))
    val registry = new DescriptorRegistry()
    val handle = registry.registerFiles(Seq(fd))

    handle should not be SchemaHandle.Empty
    registry.resolveMessage(handle, "acme.nested.Outer").isDefined shouldBe true
    registry.resolveMessage(handle, "acme.nested.Outer.Inner").isDefined shouldBe true
    registry.resolveMessage(handle, "google.protobuf.Any").isDefined shouldBe true

    val typeReg = registry.typeRegistry(handle)
    typeReg.isDefined shouldBe true
    typeReg.get.find("acme.nested.Outer.Inner") should not be null

    registry.release(handle)
    registry.resolveMessage(handle, "acme.nested.Outer") shouldBe None
  }
}
