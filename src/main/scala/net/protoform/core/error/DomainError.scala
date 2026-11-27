package net.protoform.core.error

import net.protoform.core.model.EndpointTarget

sealed abstract class DomainError(val message: String, val cause: Option[Throwable] = None) extends Exception(message, cause.orNull)

object DomainError {
  final case class EndpointUnreachable(target: EndpointTarget, reason: String, underlying: Option[Throwable] = None)
    extends DomainError(s"Cannot reach ${target.displayLabel}: $reason", underlying)

  final case class ReflectionNotSupported(target: EndpointTarget, detail: String)
    extends DomainError(s"Server reflection failed on ${target.displayLabel}: $detail")

  final case class MethodNotFound(service: String, method: String)
    extends DomainError(s"Method $service/$method was not found in schema.")

  final case class PayloadParsingFailed(rawInput: String, reason: String)
    extends DomainError(s"Failed to parse input payload: $reason")

  final case class RpcExecutionFailed(statusDescription: String, code: Int, causeDetail: Option[String] = None)
    extends DomainError(s"gRPC call failed with code $code: $statusDescription")

  final case class StorageFailure(operation: String, path: String, detail: String)
    extends DomainError(s"Storage error during $operation at $path: $detail")

  final case class SchemaParseError(source: String, detail: String)
    extends DomainError(s"Failed to load schema from $source: $detail")
}
