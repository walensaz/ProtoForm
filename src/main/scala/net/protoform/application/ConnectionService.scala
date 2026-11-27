package net.protoform.application

import io.grpc.{ManagedChannel, Status, StatusRuntimeException}
import io.grpc.health.v1.{HealthCheckRequest, HealthGrpc}
import net.protoform.core.error.DomainError
import net.protoform.core.model.EndpointTarget
import net.protoform.ports.rpc.GrpcChannelPort

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class ConnectionService(channelPort: GrpcChannelPort) {

  def connect(target: EndpointTarget, maxInboundMessageMb: Int = 32): Either[DomainError, ManagedChannel] = {
    channelPort.getOrCreateChannel(target, maxInboundMessageMb)
  }

  def checkHealth(channel: ManagedChannel, serviceName: String = "")(implicit ec: ExecutionContext): Future[Either[String, String]] = {
    Future {
      try {
        val stub = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
        val response = stub.check(HealthCheckRequest.newBuilder().setService(serviceName).build())
        Right(response.getStatus.name())
      } catch {
        case sre: StatusRuntimeException =>
          val code = sre.getStatus.getCode
          if (code == Status.Code.UNIMPLEMENTED) Left("Health service not implemented (UNIMPLEMENTED)")
          else Left(s"${code.name()}: ${Option(sre.getStatus.getDescription).getOrElse(sre.getMessage)}")
        case NonFatal(e) =>
          Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
      }
    }
  }

  def disconnect(target: EndpointTarget): Unit = {
    channelPort.shutdownChannel(target)
  }

  def shutdown(): Unit = {
    channelPort.shutdownAll()
  }
}
