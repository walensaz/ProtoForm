package net.protoform.application

import io.grpc.Channel
import net.protoform.core.error.DomainError
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.ServiceSchema
import net.protoform.ports.reflection.ServerReflectionPort

import scala.concurrent.Future

class ReflectionService(reflectionPort: ServerReflectionPort) {

  def introspect(
    target: EndpointTarget,
    channel: Channel,
    timeoutSeconds: Int = 15
  ): Future[Either[DomainError, List[ServiceSchema]]] = {
    val timeoutMs = Math.max(1, timeoutSeconds).toLong * 1000L
    reflectionPort.discoverServices(target, channel, timeoutMs)
  }
}
