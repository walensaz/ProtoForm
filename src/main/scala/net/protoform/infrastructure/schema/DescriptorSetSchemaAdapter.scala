package net.protoform.infrastructure.schema

import com.google.protobuf.DescriptorProtos
import net.protoform.core.error.DomainError
import net.protoform.core.schema.ServiceSchema
import net.protoform.infrastructure.reflection.ProtobufSchemaConverter
import net.protoform.ports.schema.SchemaParserPort

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class DescriptorSetSchemaAdapter(
  registry: DescriptorRegistry = DescriptorRegistry.shared
) extends SchemaParserPort {

  override def loadDescriptorSet(filePath: Path): Either[DomainError, List[ServiceSchema]] = {
    try {
      if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
        return Left(DomainError.SchemaParseError(filePath.toString, "Descriptor set file does not exist"))
      }
      val bytes = Files.readAllBytes(filePath)
      val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(bytes)
      val protosByName = descriptorSet.getFileList.asScala.map(p => p.getName -> p).toMap
      val built = ProtobufSchemaConverter.buildFileDescriptors(protosByName)
      val services = ProtobufSchemaConverter.assembleServiceSchemas(serviceNamesFilter = None, built, registry)
      if (services.isEmpty) {
        Left(DomainError.SchemaParseError(filePath.toString, "Descriptor set contained no gRPC services"))
      } else {
        Right(services)
      }
    } catch {
      case NonFatal(e) =>
        Left(DomainError.SchemaParseError(
          filePath.toString,
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        ))
    }
  }
}
