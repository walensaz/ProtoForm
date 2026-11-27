package net.protoform.core.schema

import java.util.UUID

final case class SchemaHandle(value: String) extends AnyVal

object SchemaHandle {
  val Empty: SchemaHandle = SchemaHandle("")
  def random(): SchemaHandle = SchemaHandle(UUID.randomUUID().toString)
}
