package net.protoform.application

import com.google.gson.{GsonBuilder, JsonParser}
import net.protoform.core.schema.{FieldSchema, MethodSchema, ProtoType}

import scala.collection.mutable
import scala.util.control.NonFatal

object FormSchemaService {
  val MaxRecursionDepth = 8
  private val prettyGson = new GsonBuilder().setPrettyPrinting().serializeNulls().create()

  private val wellKnownDefaults: Map[String, String] = Map(
    "google.protobuf.Empty"       -> "{}",
    "google.protobuf.Struct"      -> "{}",
    "google.protobuf.Value"       -> "null",
    "google.protobuf.ListValue"   -> "[]",
    "google.protobuf.Timestamp"   -> "\"1970-01-01T00:00:00Z\"",
    "google.protobuf.Duration"    -> "\"0s\"",
    "google.protobuf.FieldMask"   -> "\"\"",
    "google.protobuf.BoolValue"   -> "false",
    "google.protobuf.StringValue" -> "\"\"",
    "google.protobuf.BytesValue"  -> "\"\"",
    "google.protobuf.Int32Value"  -> "0",
    "google.protobuf.UInt32Value" -> "0",
    "google.protobuf.Int64Value"  -> "0",
    "google.protobuf.UInt64Value" -> "0",
    "google.protobuf.FloatValue"  -> "0.0",
    "google.protobuf.DoubleValue" -> "0.0",
    "google.protobuf.Any"         -> "{}"
  )

  def formatJson(rawJson: String): Either[String, String] = {
    val trimmed = Option(rawJson).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) {
      Right("{}")
    } else {
      try {
        val el = JsonParser.parseString(trimmed)
        Right(prettyGson.toJson(el))
      } catch {
        case NonFatal(e) =>
          Left(Option(e.getMessage).getOrElse("Invalid JSON"))
      }
    }
  }
}

class FormSchemaService {
  import FormSchemaService.{MaxRecursionDepth, wellKnownDefaults}

  def generateDefaultJson(
    method: MethodSchema,
    includeDefaultFields: Boolean = true,
    preserveProtoFieldNames: Boolean = true
  ): String = {
    generateDefaultJsonForMessage(method.inputSchema, includeDefaultFields, preserveProtoFieldNames)
  }

  def generateDefaultJsonForMessage(
    message: ProtoType.MessageType,
    includeDefaultFields: Boolean = true,
    preserveProtoFieldNames: Boolean = true
  ): String = {
    if (!includeDefaultFields) {
      "{}"
    } else {
      buildMessageDefaultsJson(message, visited = Set.empty, depth = 0, preserveProtoFieldNames)
    }
  }

  private[application] def buildMessageDefaultsJson(
    message: ProtoType.MessageType,
    visited: Set[String] = Set.empty,
    depth: Int = 0,
    preserveProtoFieldNames: Boolean = true
  ): String = {
    val wellKnownOverride =
      if (depth > 0) wellKnownDefaults.get(message.fullName)
      else if (message.fullName == "google.protobuf.Struct" || message.fullName == "google.protobuf.Empty") Some("{}")
      else None

    wellKnownOverride match {
      case Some(v) =>
        v
      case None if depth >= MaxRecursionDepth || visited.contains(message.fullName) =>
        "{}"
      case None =>
        val seenOneofs = mutable.Set.empty[String]
        val fields = message.fields().filter { field =>
          if (field.isOptional) {
            false
          } else {
            field.oneofGroup match {
              case Some(groupName) => seenOneofs.add(groupName)
              case None            => true
            }
          }
        }

        if (fields.isEmpty) {
          "{}"
        } else {
          val nextVisited = visited + message.fullName
          val indent = "  " * (depth + 1)
          val closingIndent = "  " * depth

          val entries = fields.map { field =>
            val fieldName = if (preserveProtoFieldNames) field.name else field.jsonName
            val valueJson = defaultFieldJson(field, nextVisited, depth + 1, preserveProtoFieldNames)
            s"""$indent"$fieldName": $valueJson"""
          }

          s"{\n${entries.mkString(",\n")}\n$closingIndent}"
        }
    }
  }

  private def defaultFieldJson(
    field: FieldSchema,
    visited: Set[String],
    nextDepth: Int,
    preserveProtoFieldNames: Boolean
  ): String = {
    if (field.isMap) {
      "{}"
    } else if (field.isRepeated) {
      "[]"
    } else {
      field.protoType match {
        case nested: ProtoType.MessageType =>
          buildMessageDefaultsJson(nested, visited, nextDepth, preserveProtoFieldNames)
        case scalarOrEnum =>
          defaultScalarJson(scalarOrEnum)
      }
    }
  }

  private def defaultScalarJson(protoType: ProtoType): String = {
    protoType.defaultScalarValue match {
      case s: String  => s""""$s""""
      case b: Boolean => b.toString
      case d: Double  => d.toString
      case f: Float   => f.toString
      case l: Long    => l.toString
      case i: Int     => i.toString
      case _          => "\"\""
    }
  }
}
