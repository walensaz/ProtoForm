package net.protoform.ports.rpc

import io.grpc.Channel
import net.protoform.core.auth.MetadataHeader
import net.protoform.core.call.{RpcCall, RpcCallOptions, RpcCallStatus, StreamChunk}
import net.protoform.core.error.DomainError
import net.protoform.core.model.{CallId, EndpointTarget}
import net.protoform.core.schema.MethodSchema

import scala.concurrent.Future

trait ActiveClientStream {
  def callId: CallId
  def sendNext(requestJson: String): Either[DomainError, Unit]
  def complete(): Unit
  def cancel(reason: String = "Cancelled by user"): Unit
}

trait DynamicCallPort {
  def executeUnary(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    callOptions: RpcCallOptions = RpcCallOptions()
  ): Future[Either[DomainError, RpcCall]]

  def executeServerStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    onChunk: StreamChunk => Unit,
    onComplete: RpcCallStatus => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): CallId

  def executeClientStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    onComplete: Either[DomainError, RpcCall] => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): ActiveClientStream

  def executeBidiStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    onChunk: StreamChunk => Unit,
    onComplete: RpcCallStatus => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): ActiveClientStream

  def cancelCall(callId: CallId): Unit
}
