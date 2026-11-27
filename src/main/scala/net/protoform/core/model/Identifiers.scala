package net.protoform.core.model

import java.util.UUID

opaque type TargetId = String
object TargetId {
  def apply(value: String): TargetId = value
  def random(): TargetId = UUID.randomUUID().toString
  extension (id: TargetId) {
    def value: String = id
  }
}

opaque type CallId = String
object CallId {
  def apply(value: String): CallId = value
  def random(): CallId = UUID.randomUUID().toString
  extension (id: CallId) {
    def value: String = id
  }
}
