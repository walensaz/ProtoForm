package net.protoform.ports.auth

import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import net.protoform.core.error.DomainError

trait DynamicAuthTokenPort {
  def resolveHeaders(preset: AuthPreset): Either[DomainError, List[MetadataHeader]]
}
