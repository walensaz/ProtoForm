package net.protoform.application

import com.google.protobuf.{DescriptorProtos, Descriptors, DynamicMessage, Struct, Value}
import com.google.protobuf.util.JsonFormat
import net.protoform.infrastructure.reflection.ProtobufSchemaConverter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FormSchemaServiceSpec extends AnyFunSuite with Matchers {

  private val service = new FormSchemaService()

  private def buildFileDescriptor(
    fileName: String,
    pkg: String,
    messages: Seq[DescriptorProtos.DescriptorProto]
  ): Descriptors.FileDescriptor = {
    val builder = DescriptorProtos.FileDescriptorProto.newBuilder()
      .setName(fileName)
      .setPackage(pkg)
      .setSyntax("proto3")
    messages.foreach(builder.addMessageType)
    Descriptors.FileDescriptor.buildFrom(builder.build(), Array.empty)
  }

  private def field(
    name: String,
    number: Int,
    fieldType: DescriptorProtos.FieldDescriptorProto.Type,
    label: DescriptorProtos.FieldDescriptorProto.Label = DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL,
    typeName: Option[String] = None,
    oneofIndex: Option[Int] = None
  ): DescriptorProtos.FieldDescriptorProto = {
    val b = DescriptorProtos.FieldDescriptorProto.newBuilder()
      .setName(name)
      .setNumber(number)
      .setType(fieldType)
      .setLabel(label)
    typeName.foreach(b.setTypeName)
    oneofIndex.foreach(b.setOneofIndex)
    b.build()
  }

  test("FormSchemaService should include all default values for protobuf message") {
    val msg = ProtobufSchemaConverter.convertMessageType(DescriptorProtos.FieldDescriptorProto.getDescriptor)
    val json = service.generateDefaultJsonForMessage(msg)

    json should not be empty
    json should include("name")
    json should include("number")
    json should include("0")
  }

  test("buildMessageDefaultsJson terminates cleanly on direct self-referential messages") {
    val nodeMsg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("TreeNode")
      .addField(field("node_label", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
      .addField(
        field(
          "next_node",
          2,
          DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE,
          typeName = Some(".test.cyclic.TreeNode")
        )
      )
      .build()

    val fd = buildFileDescriptor("cyclic.proto", "test.cyclic", Seq(nodeMsg))
    val desc = fd.findMessageTypeByName("TreeNode")
    val msgType = ProtobufSchemaConverter.convertMessageType(desc)

    val json = service.buildMessageDefaultsJson(msgType)
    json should include(""""node_label": """"")
    json should include(""""next_node": {}""")

    val builder = DynamicMessage.newBuilder(desc)
    noException should be thrownBy JsonFormat.parser().merge(json, builder)
  }

  test("buildMessageDefaultsJson terminates cleanly on mutually recursive messages and Struct/Value") {
    val msgA = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("NodeA")
      .addField(
        field(
          "ref_b",
          1,
          DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE,
          typeName = Some(".test.mutual.NodeB")
        )
      )
      .build()
    val msgB = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("NodeB")
      .addField(
        field(
          "ref_a",
          1,
          DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE,
          typeName = Some(".test.mutual.NodeA")
        )
      )
      .build()

    val fd = buildFileDescriptor("mutual.proto", "test.mutual", Seq(msgA, msgB))
    val descA = fd.findMessageTypeByName("NodeA")
    val msgTypeA = ProtobufSchemaConverter.convertMessageType(descA)

    val jsonA = service.generateDefaultJsonForMessage(msgTypeA)
    jsonA should include(""""ref_b":""")
    jsonA should include(""""ref_a": {}""")

    noException should be thrownBy service.generateDefaultJsonForMessage(ProtobufSchemaConverter.convertMessageType(Struct.getDescriptor))
    noException should be thrownBy service.generateDefaultJsonForMessage(ProtobufSchemaConverter.convertMessageType(Value.getDescriptor))
  }

  test("buildMessageDefaultsJson emits at most one field per oneof group") {
    val oneofMsg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("PaymentChoice")
      .addOneofDecl(DescriptorProtos.OneofDescriptorProto.newBuilder().setName("method").build())
      .addField(
        field(
          "card_number",
          1,
          DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING,
          oneofIndex = Some(0)
        )
      )
      .addField(
        field(
          "bank_account",
          2,
          DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64,
          oneofIndex = Some(0)
        )
      )
      .addField(
        field(
          "wallet_id",
          3,
          DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING,
          oneofIndex = Some(0)
        )
      )
      .build()

    val fd = buildFileDescriptor("oneof.proto", "test.oneof", Seq(oneofMsg))
    val desc = fd.findMessageTypeByName("PaymentChoice")

    val json = service.generateDefaultJsonForMessage(ProtobufSchemaConverter.convertMessageType(desc))
    json should include(""""card_number": """"")
    json should not include "bank_account"
    json should not include "wallet_id"

    val builder = DynamicMessage.newBuilder(desc)
    noException should be thrownBy JsonFormat.parser().merge(json, builder)
  }

  test("buildMessageDefaultsJson emits empty object for map fields and empty string for bytes") {
    val mapEntry = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("TagsEntry")
      .setOptions(DescriptorProtos.MessageOptions.newBuilder().setMapEntry(true).build())
      .addField(field("key", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
      .addField(field("value", 2, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32))
      .build()

    val payloadMsg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("Payload")
      .addNestedType(mapEntry)
      .addField(
        field(
          "label_tags",
          1,
          DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE,
          label = DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED,
          typeName = Some(".test.types.Payload.TagsEntry")
        )
      )
      .addField(field("raw_bytes", 2, DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES))
      .build()

    val fd = buildFileDescriptor("types.proto", "test.types", Seq(payloadMsg))
    val desc = fd.findMessageTypeByName("Payload")

    val json = service.buildMessageDefaultsJson(ProtobufSchemaConverter.convertMessageType(desc))
    json should include(""""label_tags": {}""")
    json should include(""""raw_bytes": """"")

    val builder = DynamicMessage.newBuilder(desc)
    noException should be thrownBy JsonFormat.parser().merge(json, builder)
  }

  test("field names use proto snake_case consistently") {
    val msg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("UserProfile")
      .addField(field("first_name", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
      .addField(field("account_balance", 2, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64))
      .addField(field("is_active", 3, DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL))
      .build()

    val fd = buildFileDescriptor("profile.proto", "test.profile", Seq(msg))
    val msgType = ProtobufSchemaConverter.convertMessageType(fd.findMessageTypeByName("UserProfile"))

    val json = service.generateDefaultJsonForMessage(msgType)
    json should include(""""first_name"""")
    json should include(""""account_balance"""")
    json should include(""""is_active"""")
    json should not include "firstName"
    json should not include "accountBalance"
    json should not include "isActive"
  }

  test("generateDefaultJsonForMessage respects includeDefaultFields and preserveProtoFieldNames") {
    val msg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("UserProfile")
      .addField(field("first_name", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
      .build()
    val fd = buildFileDescriptor("prefs.proto", "test.prefs", Seq(msg))
    val msgType = ProtobufSchemaConverter.convertMessageType(fd.findMessageTypeByName("UserProfile"))

    service.generateDefaultJsonForMessage(msgType, includeDefaultFields = false) shouldBe "{}"
    val camelJson = service.generateDefaultJsonForMessage(msgType, includeDefaultFields = true, preserveProtoFieldNames = false)
    camelJson should include(""""firstName"""")
    camelJson should not include "first_name"

    FormSchemaService.formatJson("""{"a":1,"b":[2,3]}""").isRight shouldBe true
    FormSchemaService.formatJson("{invalid").isLeft shouldBe true
  }

  test("buildMessageDefaultsJson omits proto3 optional fields while keeping regular fields") {
    val reqOptField = DescriptorProtos.FieldDescriptorProto.newBuilder()
      .setName("nickname")
      .setNumber(2)
      .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
      .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
      .setOneofIndex(0)
      .setProto3Optional(true)
      .build()

    val optIntField = DescriptorProtos.FieldDescriptorProto.newBuilder()
      .setName("retry_limit")
      .setNumber(3)
      .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
      .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
      .setOneofIndex(1)
      .setProto3Optional(true)
      .build()

    val msg = DescriptorProtos.DescriptorProto.newBuilder()
      .setName("OptionalSample")
      .addOneofDecl(DescriptorProtos.OneofDescriptorProto.newBuilder().setName("_nickname").build())
      .addOneofDecl(DescriptorProtos.OneofDescriptorProto.newBuilder().setName("_retry_limit").build())
      .addField(field("required_id", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
      .addField(reqOptField)
      .addField(optIntField)
      .build()

    val fd = buildFileDescriptor("optional_sample.proto", "test.opt", Seq(msg))
    val msgType = ProtobufSchemaConverter.convertMessageType(fd.findMessageTypeByName("OptionalSample"))
    val fields = msgType.fields()

    fields.find(_.name == "required_id").exists(!_.isOptional) shouldBe true
    fields.find(_.name == "nickname").exists(f => f.isOptional && f.oneofGroup.isEmpty) shouldBe true
    fields.find(_.name == "retry_limit").exists(f => f.isOptional && f.oneofGroup.isEmpty) shouldBe true

    val json = service.generateDefaultJsonForMessage(msgType, includeDefaultFields = true)
    json should include(""""required_id": """"")
    json should not include "nickname"
    json should not include "retry_limit"
  }
}
