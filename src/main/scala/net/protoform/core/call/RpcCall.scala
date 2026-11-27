package net.protoform.core.call

import net.protoform.core.model.{CallId, EndpointTarget}
import net.protoform.core.schema.MethodSchema

sealed trait RpcCallStatus
object RpcCallStatus {
  case object Idle extends RpcCallStatus
  case object Connecting extends RpcCallStatus
  case object InFlight extends RpcCallStatus
  case object Streaming extends RpcCallStatus
  final case class Completed(code: Int, description: String, latencyMs: Long) extends RpcCallStatus
  final case class Failed(code: Int, description: String, cause: Option[String] = None) extends RpcCallStatus
}

enum StreamDirection {
  case Sent
  case Received
}

final case class StreamChunk(
  index: Long,
  timestampMs: Long,
  payloadJson: String,
  direction: StreamDirection = StreamDirection.Received
)

final case class RpcCall(
  id: CallId = CallId.random(),
  target: EndpointTarget,
  method: MethodSchema,
  status: RpcCallStatus,
  requestJson: String,
  responseHeaders: Map[String, String] = Map.empty,
  responseTrailers: Map[String, String] = Map.empty,
  responseBodyJson: Option[String] = None,
  streamMessages: List[StreamChunk] = List.empty,
  latencyMs: Option[Long] = None
)
