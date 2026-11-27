package net.protoform.ports.schema

import net.protoform.core.error.DomainError
import net.protoform.core.schema.ServiceSchema

import java.nio.file.Path

trait SchemaParserPort {
  def loadDescriptorSet(filePath: Path): Either[DomainError, List[ServiceSchema]]
}
