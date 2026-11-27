package net.protoform.infrastructure.reflection

import com.google.protobuf.{DescriptorProtos, Descriptors}
import io.grpc.{CallOptions, Channel, ClientCall, MethodDescriptor, Status}
import io.grpc.reflection.v1alpha
import io.grpc.stub.{ClientCalls, StreamObserver}
import net.protoform.core.error.DomainError
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema._
import net.protoform.infrastructure.schema.DescriptorRegistry
import net.protoform.ports.reflection.ServerReflectionPort

import java.util.concurrent.{Executors, ScheduledExecutorService, ScheduledFuture, ThreadFactory, TimeUnit}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object GrpcServerReflectionAdapter {

  private val V1AlphaMethod: MethodDescriptor[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse] =
    v1alpha.ServerReflectionGrpc.getServerReflectionInfoMethod()

  // grpc.reflection.v1 is wire-compatible with v1alpha; grpc-services 1.64.0 only ships v1alpha stubs.
  private val V1Method: MethodDescriptor[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse] =
    V1AlphaMethod.toBuilder()
      .setFullMethodName("grpc.reflection.v1.ServerReflection/ServerReflectionInfo")
      .build()

  private val timeoutScheduler: ScheduledExecutorService = {
    val factory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "protoform-reflection-timeout")
        t.setDaemon(true)
        t
      }
    }
    Executors.newSingleThreadScheduledExecutor(factory)
  }

  private val wellKnownDescriptors = ProtobufSchemaConverter.wellKnownDescriptors

  private sealed trait QueryKey
  private object QueryKey {
    final case class Symbol(name: String) extends QueryKey
    final case class File(name: String) extends QueryKey
  }

  private sealed trait ReflectionAttempt
  private object ReflectionAttempt {
    final case class Success(services: List[ServiceSchema]) extends ReflectionAttempt
    case object Unimplemented extends ReflectionAttempt
    final case class Failure(error: DomainError) extends ReflectionAttempt
  }

  private def extractOriginalKey(response: v1alpha.ServerReflectionResponse): Option[QueryKey] = {
    if (!response.hasOriginalRequest) {
      None
    } else {
      val orig = response.getOriginalRequest
      orig.getMessageRequestCase match {
        case v1alpha.ServerReflectionRequest.MessageRequestCase.FILE_CONTAINING_SYMBOL =>
          Some(QueryKey.Symbol(orig.getFileContainingSymbol))
        case v1alpha.ServerReflectionRequest.MessageRequestCase.FILE_BY_FILENAME =>
          Some(QueryKey.File(orig.getFileByFilename))
        case _ =>
          None
      }
    }
  }

  private def parseProtos(
    rawProtos: Iterable[com.google.protobuf.ByteString]
  ): List[DescriptorProtos.FileDescriptorProto] = {
    rawProtos.flatMap { bytes =>
      try {
        Some(DescriptorProtos.FileDescriptorProto.parseFrom(bytes))
      } catch {
        case NonFatal(_) => None
      }
    }.toList
  }
}

class GrpcServerReflectionAdapter(
  timeoutMillis: Long = 15000L,
  registry: DescriptorRegistry = DescriptorRegistry.shared
) extends ServerReflectionPort {
  import GrpcServerReflectionAdapter._

  override def discoverServices(
    target: EndpointTarget,
    channel: Channel
  ): Future[Either[DomainError, List[ServiceSchema]]] = {
    discoverServices(target, channel, timeoutMillis)
  }

  override def discoverServices(
    target: EndpointTarget,
    channel: Channel,
    effectiveTimeoutMillis: Long
  ): Future[Either[DomainError, List[ServiceSchema]]] = {
    implicit val ec: ExecutionContext = ExecutionContext.parasitic
    executeReflection(target, channel, V1Method, effectiveTimeoutMillis).flatMap {
      case ReflectionAttempt.Success(services) =>
        Future.successful(Right(services))
      case ReflectionAttempt.Unimplemented =>
        executeReflection(target, channel, V1AlphaMethod, effectiveTimeoutMillis).map {
          case ReflectionAttempt.Success(services) =>
            Right(services)
          case ReflectionAttempt.Unimplemented =>
            Left(DomainError.ReflectionNotSupported(target, "Server does not implement gRPC reflection (v1 or v1alpha)."))
          case ReflectionAttempt.Failure(err) =>
            Left(err)
        }
      case ReflectionAttempt.Failure(err) =>
        Future.successful(Left(err))
    }
  }

  private def executeReflection(
    target: EndpointTarget,
    channel: Channel,
    method: MethodDescriptor[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse],
    effectiveTimeoutMillis: Long
  ): Future[ReflectionAttempt] = {
    val promise = Promise[ReflectionAttempt]()
    val stateLock = new AnyRef

    var finished = false
    var awaitingListServices = true
    var discoveredServiceNames = List.empty[String]
    val fileDescriptorProtosByName = mutable.Map.empty[String, DescriptorProtos.FileDescriptorProto]
    val pendingQueries = mutable.LinkedHashSet.empty[QueryKey]
    val requestedFiles = mutable.Set.empty[String]

    @volatile var callRef: Option[ClientCall[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse]] = None
    @volatile var requestStreamRef: Option[StreamObserver[v1alpha.ServerReflectionRequest]] = None
    @volatile var timeoutTaskRef: Option[ScheduledFuture[_]] = None

    def completePromise(attempt: ReflectionAttempt): Unit = {
      timeoutTaskRef.foreach(_.cancel(false))
      promise.trySuccess(attempt)
    }

    def safeCompleteStream(): Unit = {
      requestStreamRef.foreach { stream =>
        try {
          stream.onCompleted()
        } catch {
          case NonFatal(_) => ()
        }
      }
    }

    val responseObserver = new StreamObserver[v1alpha.ServerReflectionResponse] {
      override def onNext(response: v1alpha.ServerReflectionResponse): Unit = {
        val resultToFinish: Option[ReflectionAttempt] = stateLock.synchronized {
          if (finished) {
            None
          } else {
            response.getMessageResponseCase match {
              case v1alpha.ServerReflectionResponse.MessageResponseCase.LIST_SERVICES_RESPONSE =>
                val names = response.getListServicesResponse.getServiceList.asScala.map(_.getName).toList
                val filtered = names.filterNot(isReflectionService)
                discoveredServiceNames = filtered
                awaitingListServices = false

                if (filtered.isEmpty) {
                  finished = true
                  safeCompleteStream()
                  Some(ReflectionAttempt.Success(Nil))
                } else {
                  filtered.foreach { serviceName =>
                    val key = QueryKey.Symbol(serviceName)
                    if (pendingQueries.add(key)) {
                      requestStreamRef.foreach { stream =>
                        stream.onNext(
                          v1alpha.ServerReflectionRequest.newBuilder()
                            .setFileContainingSymbol(serviceName)
                            .build()
                        )
                      }
                    }
                  }
                  None
                }

              case v1alpha.ServerReflectionResponse.MessageResponseCase.FILE_DESCRIPTOR_RESPONSE =>
                val originalKey = extractOriginalKey(response)
                val protos = parseProtos(response.getFileDescriptorResponse.getFileDescriptorProtoList.asScala)
                protos.foreach { proto =>
                  fileDescriptorProtosByName.put(proto.getName, proto)
                  requestedFiles.add(proto.getName)
                }

                correlateCompletedQuery(originalKey, protos, pendingQueries)
                requestMissingDependencies(protos, fileDescriptorProtosByName, requestedFiles, pendingQueries, requestStreamRef)

                if (!awaitingListServices && pendingQueries.isEmpty) {
                  finished = true
                  safeCompleteStream()
                  val built = ProtobufSchemaConverter.buildFileDescriptors(fileDescriptorProtosByName.toMap)
                  val schemas = ProtobufSchemaConverter.assembleServiceSchemas(Some(discoveredServiceNames.toSet), built, registry)
                  Some(ReflectionAttempt.Success(schemas))
                } else {
                  None
                }

              case v1alpha.ServerReflectionResponse.MessageResponseCase.ERROR_RESPONSE =>
                val err = response.getErrorResponse
                finished = true
                safeCompleteStream()
                if (err.getErrorCode == Status.Code.UNIMPLEMENTED.value()) {
                  Some(ReflectionAttempt.Unimplemented)
                } else {
                  Some(
                    ReflectionAttempt.Failure(
                      DomainError.ReflectionNotSupported(target, s"Code ${err.getErrorCode}: ${err.getErrorMessage}")
                    )
                  )
                }

              case _ =>
                None
            }
          }
        }
        resultToFinish.foreach(completePromise)
      }

      override def onError(t: Throwable): Unit = {
        val shouldFinish = stateLock.synchronized {
          if (finished) {
            false
          } else {
            finished = true
            true
          }
        }
        if (shouldFinish) {
          completePromise(classifyThrowable(target, t))
        }
      }

      override def onCompleted(): Unit = {
        val resultToFinish: Option[ReflectionAttempt] = stateLock.synchronized {
          if (finished) {
            None
          } else {
            finished = true
            if (!awaitingListServices && (discoveredServiceNames.isEmpty || fileDescriptorProtosByName.nonEmpty)) {
              val built = ProtobufSchemaConverter.buildFileDescriptors(fileDescriptorProtosByName.toMap)
              val schemas = ProtobufSchemaConverter.assembleServiceSchemas(Some(discoveredServiceNames.toSet), built, registry)
              Some(ReflectionAttempt.Success(schemas))
            } else {
              Some(
                ReflectionAttempt.Failure(
                  DomainError.ReflectionNotSupported(target, "Reflection stream completed prematurely.")
                )
              )
            }
          }
        }
        resultToFinish.foreach(completePromise)
      }
    }

    try {
      stateLock.synchronized {
        val call = channel.newCall(method, CallOptions.DEFAULT)
        callRef = Some(call)
        val reqStream = ClientCalls.asyncBidiStreamingCall(call, responseObserver)
        requestStreamRef = Some(reqStream)
        if (!finished) {
          reqStream.onNext(
            v1alpha.ServerReflectionRequest.newBuilder()
              .setListServices("")
              .build()
          )
        }
        // Schedule only after the call is created and initial request sent so timeout always has callRef.
        if (!finished) {
          val scheduled = timeoutScheduler.schedule(
            new Runnable {
              override def run(): Unit = {
                val activeCall = stateLock.synchronized {
                  if (finished) {
                    None
                  } else {
                    finished = true
                    callRef
                  }
                }
                activeCall.foreach { active =>
                  try {
                    active.cancel("Reflection query timed out", null)
                  } catch {
                    case NonFatal(_) => ()
                  }
                }
                promise.trySuccess(
                  ReflectionAttempt.Failure(
                    DomainError.ReflectionNotSupported(target, s"Reflection query timed out after ${effectiveTimeoutMillis}ms.")
                  )
                )
              }
            },
            effectiveTimeoutMillis,
            TimeUnit.MILLISECONDS
          )
          timeoutTaskRef = Some(scheduled)
        }
      }
    } catch {
      case NonFatal(e) =>
        val shouldFinish = stateLock.synchronized {
          if (finished) {
            false
          } else {
            finished = true
            true
          }
        }
        if (shouldFinish) {
          completePromise(classifyThrowable(target, e))
        }
    }

    promise.future
  }

  private def correlateCompletedQuery(
    originalKey: Option[QueryKey],
    protos: List[DescriptorProtos.FileDescriptorProto],
    pendingQueries: mutable.LinkedHashSet[QueryKey]
  ): Unit = {
    originalKey match {
      case Some(key) =>
        pendingQueries.remove(key)
      case None =>
        // Fallback for non-compliant servers that omit original_request.
        val returnedFiles = protos.map(_.getName).toSet
        val returnedSymbols = protos.flatMap { p =>
          val pkg = p.getPackage
          p.getServiceList.asScala.map { s =>
            if (pkg.isEmpty) s.getName else s"$pkg.${s.getName}"
          }
        }.toSet
        val matched = pendingQueries.filter {
          case QueryKey.File(f) => returnedFiles.contains(f)
          case QueryKey.Symbol(s) => returnedSymbols.contains(s)
        }.toList
        if (matched.nonEmpty) {
          matched.foreach(pendingQueries.remove)
        } else {
          pendingQueries.headOption.foreach(pendingQueries.remove)
        }
    }
  }

  private def requestMissingDependencies(
    protos: List[DescriptorProtos.FileDescriptorProto],
    fetched: mutable.Map[String, DescriptorProtos.FileDescriptorProto],
    requestedFiles: mutable.Set[String],
    pendingQueries: mutable.LinkedHashSet[QueryKey],
    requestStreamRef: Option[StreamObserver[v1alpha.ServerReflectionRequest]]
  ): Unit = {
    protos.foreach { proto =>
      proto.getDependencyList.asScala.foreach { dep =>
        if (!fetched.contains(dep) && !wellKnownDescriptors.contains(dep) && requestedFiles.add(dep)) {
          val key = QueryKey.File(dep)
          if (pendingQueries.add(key)) {
            requestStreamRef.foreach { stream =>
              stream.onNext(
                v1alpha.ServerReflectionRequest.newBuilder()
                  .setFileByFilename(dep)
                  .build()
              )
            }
          }
        }
      }
    }
  }

  private def classifyThrowable(target: EndpointTarget, t: Throwable): ReflectionAttempt = {
    val status = Status.fromThrowable(t)
    if (status.getCode == Status.Code.UNIMPLEMENTED) {
      ReflectionAttempt.Unimplemented
    } else {
      val detail = Option(status.getDescription)
        .filter(_.nonEmpty)
        .orElse(Option(t.getMessage))
        .getOrElse(status.getCode.name())
      ReflectionAttempt.Failure(DomainError.ReflectionNotSupported(target, detail))
    }
  }

  private def isReflectionService(name: String): Boolean = {
    name.startsWith("grpc.reflection.")
  }

  def convertMessageType(desc: Descriptors.Descriptor): ProtoType.MessageType = {
    ProtobufSchemaConverter.convertMessageType(desc)
  }
}
