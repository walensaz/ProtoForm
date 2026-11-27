package net.protoform.infrastructure.reflection

import com.google.protobuf.{DescriptorProtos, Descriptors}
import net.protoform.core.schema._
import net.protoform.infrastructure.schema.DescriptorRegistry

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object ProtobufSchemaConverter {

  val wellKnownDescriptors: Map[String, Descriptors.FileDescriptor] = Map(
    "google/protobuf/empty.proto" -> com.google.protobuf.EmptyProto.getDescriptor,
    "google/protobuf/any.proto" -> com.google.protobuf.AnyProto.getDescriptor,
    "google/protobuf/timestamp.proto" -> com.google.protobuf.TimestampProto.getDescriptor,
    "google/protobuf/duration.proto" -> com.google.protobuf.DurationProto.getDescriptor,
    "google/protobuf/wrappers.proto" -> com.google.protobuf.WrappersProto.getDescriptor,
    "google/protobuf/struct.proto" -> com.google.protobuf.StructProto.getDescriptor,
    "google/protobuf/field_mask.proto" -> com.google.protobuf.FieldMaskProto.getDescriptor,
    "google/protobuf/api.proto" -> com.google.protobuf.ApiProto.getDescriptor,
    "google/protobuf/type.proto" -> com.google.protobuf.TypeProto.getDescriptor,
    "google/protobuf/source_context.proto" -> com.google.protobuf.SourceContextProto.getDescriptor,
    "google/protobuf/descriptor.proto" -> com.google.protobuf.DescriptorProtos.getDescriptor
  )

  def buildFileDescriptors(
    protos: Map[String, DescriptorProtos.FileDescriptorProto]
  ): Map[String, Descriptors.FileDescriptor] = {
    val built = mutable.Map[String, Descriptors.FileDescriptor]()

    def resolveDescriptor(name: String, visited: Set[String] = Set.empty): Option[Descriptors.FileDescriptor] = {
      if (built.contains(name)) return built.get(name)
      if (wellKnownDescriptors.contains(name)) return wellKnownDescriptors.get(name)
      if (visited.contains(name)) return None

      protos.get(name).flatMap { proto =>
        val depNames = proto.getDependencyList.asScala.toList
        val depDescriptors = depNames.flatMap(dep => resolveDescriptor(dep, visited + name))
        try {
          val fd = Descriptors.FileDescriptor.buildFrom(proto, depDescriptors.toArray)
          built.put(name, fd)
          Some(fd)
        } catch {
          case NonFatal(_) => None
        }
      }
    }

    protos.keys.foreach(k => resolveDescriptor(k))
    built.toMap
  }

  def assembleServiceSchemas(
    serviceNamesFilter: Option[Set[String]],
    fileDescriptors: Map[String, Descriptors.FileDescriptor],
    registry: DescriptorRegistry = DescriptorRegistry.shared
  ): List[ServiceSchema] = {
    val matchingFiles = fileDescriptors.values.toList
    val handle = registry.registerFiles(matchingFiles)
    val allServices = for {
      fd <- matchingFiles
      svc <- fd.getServices.asScala.toList
      if !svc.getFullName.startsWith("grpc.reflection.")
      if serviceNamesFilter.forall(_.contains(svc.getFullName))
    } yield {
      val methods = svc.getMethods.asScala.toList.map(buildMethodSchema(svc.getFullName, _, handle))
      val pkg = svc.getFile.getPackage
      ServiceSchema(
        fullName = svc.getFullName,
        packageName = if (pkg.isEmpty) "default" else pkg,
        simpleName = svc.getName,
        methods = methods,
        schemaHandle = handle,
        protoFile = Option(svc.getFile.getName).map(_.trim).filter(_.nonEmpty)
      )
    }
    allServices.distinctBy(_.fullName).sortBy(_.fullName)
  }

  def buildMethodSchema(
    serviceFullName: String,
    md: Descriptors.MethodDescriptor,
    handle: SchemaHandle = SchemaHandle.Empty
  ): MethodSchema = {
    val pattern = (md.isClientStreaming, md.isServerStreaming) match {
      case (false, false) => RpcPattern.Unary
      case (false, true)  => RpcPattern.ServerStreaming
      case (true, false)  => RpcPattern.ClientStreaming
      case (true, true)   => RpcPattern.BidirectionalStreaming
    }

    MethodSchema(
      name = md.getName,
      fullMethodName = s"$serviceFullName/${md.getName}",
      serviceFullName = serviceFullName,
      pattern = pattern,
      inputSchema = convertMessageType(md.getInputType),
      outputSchema = convertMessageType(md.getOutputType),
      schemaHandle = handle
    )
  }

  def convertMessageType(desc: Descriptors.Descriptor): ProtoType.MessageType = {
    ProtoType.MessageType(
      fullName = desc.getFullName,
      fields = () => desc.getFields.asScala.toList.map(convertField)
    )
  }

  private def convertField(fd: Descriptors.FieldDescriptor): FieldSchema = {
    val protoType = resolveProtoType(fd)
    val defaultValue = resolveDefaultValue(fd, protoType)
    val oneof = Option(fd.getRealContainingOneof).map(_.getName)

    FieldSchema(
      name = fd.getName,
      jsonName = fd.getJsonName,
      number = fd.getNumber,
      protoType = protoType,
      isRepeated = fd.isRepeated && !fd.isMapField,
      isRequired = fd.isRequired,
      defaultValue = defaultValue,
      oneofGroup = oneof,
      documentation = None,
      isOptional = fd.toProto.getProto3Optional
    )
  }

  private def resolveProtoType(fd: Descriptors.FieldDescriptor): ProtoType = {
    import Descriptors.FieldDescriptor.Type._
    if (fd.isMapField) {
      val entryDesc = fd.getMessageType
      val keyField = entryDesc.findFieldByNumber(1)
      val valueField = entryDesc.findFieldByNumber(2)
      ProtoType.MapType(resolveProtoType(keyField), resolveProtoType(valueField))
    } else {
      fd.getType match {
        case DOUBLE   => ProtoType.DoubleType
        case FLOAT    => ProtoType.FloatType
        case INT64    => ProtoType.Int64Type
        case UINT64   => ProtoType.UInt64Type
        case INT32    => ProtoType.Int32Type
        case FIXED64  => ProtoType.Fixed64Type
        case FIXED32  => ProtoType.Fixed32Type
        case BOOL     => ProtoType.BoolType
        case STRING   => ProtoType.StringType
        case BYTES    => ProtoType.BytesType
        case UINT32   => ProtoType.UInt32Type
        case SFIXED32 => ProtoType.SFixed32Type
        case SFIXED64 => ProtoType.SFixed64Type
        case SINT32   => ProtoType.SInt32Type
        case SINT64   => ProtoType.SInt64Type
        case ENUM =>
          val values = fd.getEnumType.getValues.asScala.toList.map(ev => ProtoType.EnumValue(ev.getName, ev.getNumber))
          ProtoType.EnumType(fd.getEnumType.getFullName, values)
        case MESSAGE =>
          convertMessageType(fd.getMessageType)
        case GROUP =>
          ProtoType.MessageType(fd.getMessageType.getFullName, () => Nil)
      }
    }
  }

  private def resolveDefaultValue(fd: Descriptors.FieldDescriptor, protoType: ProtoType): Any = {
    if (fd.isRepeated) {
      if (fd.isMapField) Map.empty[Any, Any] else List.empty[Any]
    } else {
      protoType.defaultScalarValue
    }
  }
}
