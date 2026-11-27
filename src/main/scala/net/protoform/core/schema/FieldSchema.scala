package net.protoform.core.schema

final case class FieldSchema(
  name: String,
  jsonName: String,
  number: Int,
  protoType: ProtoType,
  isRepeated: Boolean,
  isRequired: Boolean,
  defaultValue: Any,
  oneofGroup: Option[String] = None,
  documentation: Option[String] = None,
  isOptional: Boolean = false
) {
  def isMap: Boolean = protoType.isInstanceOf[ProtoType.MapType]
  def isMessage: Boolean = protoType.isInstanceOf[ProtoType.MessageType]
  def isEnum: Boolean = protoType.isInstanceOf[ProtoType.EnumType]
}
