package net.protoform.ports.reflection

import io.grpc.Channel
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.ServiceSchema
import net.protoform.core.error.DomainError
import scala.concurrent.Future

trait ServerReflectionPort {
  def discoverServices(target: EndpointTarget, channel: Channel): Future[Either[DomainError, List[ServiceSchema]]]
  def discoverServices(target: EndpointTarget, channel: Channel, timeoutMillis: Long): Future[Either[DomainError, List[ServiceSchema]]] =
    discoverServices(target, channel)
}
