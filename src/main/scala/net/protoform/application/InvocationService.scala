package net.protoform.application

import io.grpc.Channel
import net.protoform.core.auth.MetadataHeader
import net.protoform.core.call.{RpcCall, RpcCallOptions, RpcCallStatus, StreamChunk}
import net.protoform.core.error.DomainError
import net.protoform.core.model.{CallId, EndpointTarget}
import net.protoform.core.schema.MethodSchema
import net.protoform.ports.rpc.{ActiveClientStream, DynamicCallPort}

import scala.concurrent.Future

class InvocationService(dynamicCallPort: DynamicCallPort) {

  def invokeUnary(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    callOptions: RpcCallOptions = RpcCallOptions()
  ): Future[Either[DomainError, RpcCall]] = {
    dynamicCallPort.executeUnary(channel, target, method, requestJson, headers, callOptions)
  }

  def invokeServerStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    onChunk: StreamChunk => Unit,
    onComplete: RpcCallStatus => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): CallId = {
    dynamicCallPort.executeServerStreaming(channel, target, method, requestJson, headers, onChunk, onComplete, callOptions)
  }

  def invokeClientStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    onComplete: Either[DomainError, RpcCall] => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): ActiveClientStream = {
    dynamicCallPort.executeClientStreaming(channel, target, method, headers, onComplete, callOptions)
  }

  def invokeBidiStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    onChunk: StreamChunk => Unit,
    onComplete: RpcCallStatus => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): ActiveClientStream = {
    dynamicCallPort.executeBidiStreaming(channel, target, method, headers, onChunk, onComplete, callOptions)
  }

  def cancel(callId: CallId): Unit = {
    dynamicCallPort.cancelCall(callId)
  }
}
