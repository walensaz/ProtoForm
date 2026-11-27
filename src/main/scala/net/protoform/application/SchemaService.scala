package net.protoform.application

import net.protoform.core.error.DomainError
import net.protoform.core.schema.ServiceSchema
import net.protoform.ports.schema.{ProtoSourceParserPort, SchemaParserPort}

import java.nio.file.Path

class SchemaService(
  schemaParserPort: SchemaParserPort,
  protoSourceParserPort: Option[ProtoSourceParserPort] = None
) {
  def loadDescriptorSet(filePath: Path): Either[DomainError, List[ServiceSchema]] = {
    schemaParserPort.loadDescriptorSet(filePath)
  }

  def loadProtoFiles(
    protoFiles: List[Path],
    includeDirs: List[Path],
    protocExecutable: Option[String] = None
  ): Either[DomainError, List[ServiceSchema]] = {
    protoSourceParserPort match {
      case Some(port) => port.loadProtoFiles(protoFiles, includeDirs, protocExecutable)
      case None       => Left(DomainError.SchemaParseError("protoc", "Raw .proto source loader is not configured."))
    }
  }
}
