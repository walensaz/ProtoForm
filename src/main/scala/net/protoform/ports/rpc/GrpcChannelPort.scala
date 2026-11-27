package net.protoform.ports.rpc

import io.grpc.ManagedChannel
import net.protoform.core.model.EndpointTarget
import net.protoform.core.error.DomainError

trait GrpcChannelPort {
  def getOrCreateChannel(target: EndpointTarget): Either[DomainError, ManagedChannel]
  def getOrCreateChannel(target: EndpointTarget, maxInboundMessageMb: Int): Either[DomainError, ManagedChannel] =
    getOrCreateChannel(target)
  def shutdownChannel(target: EndpointTarget): Unit
  def shutdownAll(): Unit
}
