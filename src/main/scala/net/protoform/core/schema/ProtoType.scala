package net.protoform.core.schema

sealed trait ProtoType {
  def typeName: String
  def defaultScalarValue: Any
}

object ProtoType {
  case object DoubleType extends ProtoType {
    val typeName = "double"
    val defaultScalarValue: Any = 0.0
  }
  case object FloatType extends ProtoType {
    val typeName = "float"
    val defaultScalarValue: Any = 0.0f
  }
  case object Int64Type extends ProtoType {
    val typeName = "int64"
    val defaultScalarValue: Any = 0L
  }
  case object UInt64Type extends ProtoType {
    val typeName = "uint64"
    val defaultScalarValue: Any = 0L
  }
  case object Int32Type extends ProtoType {
    val typeName = "int32"
    val defaultScalarValue: Any = 0
  }
  case object Fixed64Type extends ProtoType {
    val typeName = "fixed64"
    val defaultScalarValue: Any = 0L
  }
  case object Fixed32Type extends ProtoType {
    val typeName = "fixed32"
    val defaultScalarValue: Any = 0
  }
  case object BoolType extends ProtoType {
    val typeName = "bool"
    val defaultScalarValue: Any = false
  }
  case object StringType extends ProtoType {
    val typeName = "string"
    val defaultScalarValue: Any = ""
  }
  case object BytesType extends ProtoType {
    val typeName = "bytes"
    val defaultScalarValue: Any = ""
  }
  case object UInt32Type extends ProtoType {
    val typeName = "uint32"
    val defaultScalarValue: Any = 0
  }
  case object SFixed32Type extends ProtoType {
    val typeName = "sfixed32"
    val defaultScalarValue: Any = 0
  }
  case object SFixed64Type extends ProtoType {
    val typeName = "sfixed64"
    val defaultScalarValue: Any = 0L
  }
  case object SInt32Type extends ProtoType {
    val typeName = "sint32"
    val defaultScalarValue: Any = 0
  }
  case object SInt64Type extends ProtoType {
    val typeName = "sint64"
    val defaultScalarValue: Any = 0L
  }

  final case class EnumValue(name: String, number: Int)

  final case class EnumType(fullName: String, values: List[EnumValue]) extends ProtoType {
    def simpleName: String = fullName.split('.').lastOption.filter(_.nonEmpty).getOrElse(fullName)
    val typeName = s"enum $fullName"
    val defaultScalarValue: Any = values.headOption.map(_.name).getOrElse("")
  }

  final case class MessageType(fullName: String, fields: () => List[FieldSchema]) extends ProtoType {
    def simpleName: String = fullName.split('.').lastOption.filter(_.nonEmpty).getOrElse(fullName)
    val typeName = s"message $fullName"
    val defaultScalarValue: Any = Map.empty[String, Any]
  }

  final case class MapType(keyType: ProtoType, valueType: ProtoType) extends ProtoType {
    val typeName = s"map<${keyType.typeName}, ${valueType.typeName}>"
    val defaultScalarValue: Any = Map.empty[Any, Any]
  }
}
