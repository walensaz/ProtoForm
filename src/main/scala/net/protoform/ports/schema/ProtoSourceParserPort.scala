package net.protoform.ports.schema

import net.protoform.core.error.DomainError
import net.protoform.core.schema.ServiceSchema

import java.nio.file.Path

trait ProtoSourceParserPort {
  def loadProtoFiles(
    protoFiles: List[Path],
    includeDirs: List[Path],
    protocExecutable: Option[String] = None
  ): Either[DomainError, List[ServiceSchema]]
}
