package net.protoform.infrastructure.grpc

import com.google.gson.{Gson, JsonObject}
import com.google.protobuf.{Descriptors, DynamicMessage, TypeRegistry}
import com.google.protobuf.util.JsonFormat
import io.grpc.{CallOptions, Channel, ClientCall, ClientInterceptors, ForwardingClientCall, Metadata, MethodDescriptor, Status}
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.{ClientCalls, StreamObserver}
import net.protoform.core.auth.MetadataHeader
import net.protoform.core.call.{RpcCall, RpcCallOptions, RpcCallStatus, StreamChunk}
import net.protoform.core.error.DomainError
import net.protoform.core.model.{CallId, EndpointTarget}
import net.protoform.core.schema.MethodSchema
import net.protoform.infrastructure.auth.MetadataAuthInterceptor
import net.protoform.infrastructure.schema.DescriptorRegistry
import net.protoform.ports.rpc.{ActiveClientStream, DynamicCallPort}
import org.slf4j.LoggerFactory

import java.util.Base64
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class DynamicCallAdapter(
  descriptorRegistry: DescriptorRegistry = DescriptorRegistry.shared
) extends DynamicCallPort {

  private val logger = LoggerFactory.getLogger(getClass)
  private val activeCalls = new ConcurrentHashMap[String, ClientCall[_, _]]()
  private val gson = new Gson()

  private val baseParser: JsonFormat.Parser = JsonFormat.parser()
    .ignoringUnknownFields()

  override def executeUnary(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    callOptions: RpcCallOptions = RpcCallOptions()
  ): Future[Either[DomainError, RpcCall]] = {
    val promise = Promise[Either[DomainError, RpcCall]]()
    val callId = CallId.random()

    try {
      val dynamicReq = parseJsonToDynamicMessage(requestJson, method)
      val grpcMethod = buildGrpcMethodDescriptor(method, MethodDescriptor.MethodType.UNARY)
      val interceptedChannel = ClientInterceptors.intercept(channel, new MetadataAuthInterceptor(headers))
      val responseHeadersRef = new ConcurrentHashMap[String, String]()
      val responseTrailersRef = new ConcurrentHashMap[String, String]()
      val call = withMetadataCapture(
        interceptedChannel.newCall(grpcMethod, toGrpcCallOptions(callOptions)),
        responseHeadersRef,
        responseTrailersRef
      )
      activeCalls.put(callId.value, call)

      val startTime = System.currentTimeMillis()
      val responseRef = new AtomicReference[DynamicMessage]()

      ClientCalls.asyncUnaryCall(
        call,
        dynamicReq,
        new StreamObserver[DynamicMessage] {
          override def onNext(responseMsg: DynamicMessage): Unit = {
            responseRef.set(responseMsg)
          }

          override def onError(t: Throwable): Unit = {
            activeCalls.remove(callId.value)
            try {
              val status = Status.fromThrowable(t)
              val failedCall = RpcCall(
                id = callId,
                target = target,
                method = method,
                status = RpcCallStatus.Failed(
                  status.getCode.value(),
                  Option(status.getDescription).getOrElse(status.getCode.name()),
                  Option(t.getMessage)
                ),
                requestJson = requestJson,
                responseHeaders = responseHeadersRef.asScala.toMap,
                responseTrailers = responseTrailersRef.asScala.toMap,
                responseBodyJson = Some(buildErrorJson(status, t)),
                latencyMs = Some(System.currentTimeMillis() - startTime)
              )
              promise.trySuccess(Right(failedCall))
            } catch {
              case NonFatal(e) =>
                promise.trySuccess(Left(DomainError.RpcExecutionFailed(
                  Option(e.getMessage).getOrElse("Call failed"),
                  Status.Code.UNKNOWN.value(),
                  Some(e.getClass.getSimpleName)
                )))
            }
          }

          // Complete in onCompleted rather than onNext so onClose trailers are already captured.
          override def onCompleted(): Unit = {
            activeCalls.remove(callId.value)
            try {
              val latency = System.currentTimeMillis() - startTime
              val formattedResponse = Option(responseRef.get()) match {
                case Some(msg) => printMessage(msg, method, callOptions)
                case None      => "{}"
              }
              val completedCall = RpcCall(
                id = callId,
                target = target,
                method = method,
                status = RpcCallStatus.Completed(0, "OK", latency),
                requestJson = requestJson,
                responseHeaders = responseHeadersRef.asScala.toMap,
                responseTrailers = responseTrailersRef.asScala.toMap,
                responseBodyJson = Some(formattedResponse),
                latencyMs = Some(latency)
              )
              promise.trySuccess(Right(completedCall))
            } catch {
              case NonFatal(e) =>
                promise.trySuccess(Left(DomainError.RpcExecutionFailed(
                  Option(e.getMessage).getOrElse("Failed to format response"),
                  Status.Code.INTERNAL.value(),
                  Some(e.getClass.getSimpleName)
                )))
            }
          }
        }
      )
    } catch {
      case NonFatal(e) =>
        activeCalls.remove(callId.value)
        promise.trySuccess(Left(DomainError.PayloadParsingFailed(
          requestJson,
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        )))
    }

    promise.future
  }

  override def executeServerStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    onChunk: StreamChunk => Unit,
    onComplete: RpcCallStatus => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): CallId = {
    val callId = CallId.random()
    try {
      val dynamicReq = parseJsonToDynamicMessage(requestJson, method)
      val grpcMethod = buildGrpcMethodDescriptor(method, MethodDescriptor.MethodType.SERVER_STREAMING)
      val interceptedChannel = ClientInterceptors.intercept(channel, new MetadataAuthInterceptor(headers))
      val call = interceptedChannel.newCall(grpcMethod, toGrpcCallOptions(callOptions))
      activeCalls.put(callId.value, call)

      val chunkCounter = new AtomicLong(0)
      val startTime = System.currentTimeMillis()

      ClientCalls.asyncServerStreamingCall(
        call,
        dynamicReq,
        new StreamObserver[DynamicMessage] {
          override def onNext(msg: DynamicMessage): Unit = {
            try {
              val idx = chunkCounter.incrementAndGet()
              val chunkJson = printMessage(msg, method, callOptions)
              onChunk(StreamChunk(idx, System.currentTimeMillis(), chunkJson))
            } catch {
              case NonFatal(e) =>
                logger.warn(s"Failed to handle server stream chunk for call ${callId.value}: ${e.getMessage}")
            }
          }

          override def onError(t: Throwable): Unit = {
            activeCalls.remove(callId.value)
            try {
              val status = Status.fromThrowable(t)
              onComplete(RpcCallStatus.Failed(
                status.getCode.value(),
                Option(status.getDescription).getOrElse(status.getCode.name()),
                Option(t.getMessage)
              ))
            } catch {
              case NonFatal(e) =>
                logger.warn(s"Server stream onError callback failed for call ${callId.value}: ${e.getMessage}")
            }
          }

          override def onCompleted(): Unit = {
            activeCalls.remove(callId.value)
            try {
              val latency = System.currentTimeMillis() - startTime
              onComplete(RpcCallStatus.Completed(0, "Stream Completed", latency))
            } catch {
              case NonFatal(e) =>
                logger.warn(s"Server stream onCompleted callback failed for call ${callId.value}: ${e.getMessage}")
            }
          }
        }
      )
    } catch {
      case NonFatal(e) =>
        activeCalls.remove(callId.value)
        try {
          onComplete(RpcCallStatus.Failed(-1, "Preparation Failed", Option(e.getMessage)))
        } catch {
          case NonFatal(cbErr) =>
            logger.warn(s"Server stream failure callback threw for call ${callId.value}: ${cbErr.getMessage}")
        }
    }

    callId
  }

  override def executeClientStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    onComplete: Either[DomainError, RpcCall] => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): ActiveClientStream = {
    val streamCallId = CallId.random()
    val grpcMethod = buildGrpcMethodDescriptor(method, MethodDescriptor.MethodType.CLIENT_STREAMING)
    val interceptedChannel = ClientInterceptors.intercept(channel, new MetadataAuthInterceptor(headers))
    val responseHeadersRef = new ConcurrentHashMap[String, String]()
    val responseTrailersRef = new ConcurrentHashMap[String, String]()
    val call = withMetadataCapture(
      interceptedChannel.newCall(grpcMethod, toGrpcCallOptions(callOptions)),
      responseHeadersRef,
      responseTrailersRef
    )
    activeCalls.put(streamCallId.value, call)

    val startTime = System.currentTimeMillis()
    val lastResponse = new AtomicReference[DynamicMessage]()

    val responseObserver = new StreamObserver[DynamicMessage] {
      override def onNext(responseMsg: DynamicMessage): Unit = {
        lastResponse.set(responseMsg)
      }

      override def onError(t: Throwable): Unit = {
        activeCalls.remove(streamCallId.value)
        try {
          val status = Status.fromThrowable(t)
          val failed = RpcCall(
            id = streamCallId,
            target = target,
            method = method,
            status = RpcCallStatus.Failed(
              status.getCode.value(),
              Option(status.getDescription).getOrElse(status.getCode.name()),
              Option(t.getMessage)
            ),
            requestJson = "[Client Stream]",
            responseHeaders = responseHeadersRef.asScala.toMap,
            responseTrailers = responseTrailersRef.asScala.toMap,
            responseBodyJson = Some(buildErrorJson(status, t)),
            latencyMs = Some(System.currentTimeMillis() - startTime)
          )
          onComplete(Right(failed))
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Client stream onError callback failed for call ${streamCallId.value}: ${e.getMessage}")
        }
      }

      override def onCompleted(): Unit = {
        activeCalls.remove(streamCallId.value)
        try {
          val latency = System.currentTimeMillis() - startTime
          val formatted = Option(lastResponse.get()) match {
            case Some(msg) => printMessage(msg, method, callOptions)
            case None      => "{}"
          }
          val completed = RpcCall(
            id = streamCallId,
            target = target,
            method = method,
            status = RpcCallStatus.Completed(0, "OK", latency),
            requestJson = "[Client Stream Completed]",
            responseHeaders = responseHeadersRef.asScala.toMap,
            responseTrailers = responseTrailersRef.asScala.toMap,
            responseBodyJson = Some(formatted),
            latencyMs = Some(latency)
          )
          onComplete(Right(completed))
        } catch {
          case NonFatal(e) =>
            try {
              onComplete(Left(DomainError.RpcExecutionFailed(
                Option(e.getMessage).getOrElse("Failed to format response"),
                Status.Code.INTERNAL.value(),
                Some(e.getClass.getSimpleName)
              )))
            } catch {
              case NonFatal(cbErr) =>
                logger.warn(s"Client stream onCompleted callback failed for call ${streamCallId.value}: ${cbErr.getMessage}")
            }
        }
      }
    }

    val requestObserver = ClientCalls.asyncClientStreamingCall(call, responseObserver)
    createActiveClientStream(streamCallId, call, requestObserver, method)
  }

  override def executeBidiStreaming(
    channel: Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    onChunk: StreamChunk => Unit,
    onComplete: RpcCallStatus => Unit,
    callOptions: RpcCallOptions = RpcCallOptions()
  ): ActiveClientStream = {
    val streamCallId = CallId.random()
    val grpcMethod = buildGrpcMethodDescriptor(method, MethodDescriptor.MethodType.BIDI_STREAMING)
    val interceptedChannel = ClientInterceptors.intercept(channel, new MetadataAuthInterceptor(headers))
    val call = interceptedChannel.newCall(grpcMethod, toGrpcCallOptions(callOptions))
    activeCalls.put(streamCallId.value, call)

    val chunkCounter = new AtomicLong(0)
    val startTime = System.currentTimeMillis()

    val responseObserver = new StreamObserver[DynamicMessage] {
      override def onNext(msg: DynamicMessage): Unit = {
        try {
          val idx = chunkCounter.incrementAndGet()
          val chunkJson = printMessage(msg, method, callOptions)
          onChunk(StreamChunk(idx, System.currentTimeMillis(), chunkJson))
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Failed to handle bidi stream chunk for call ${streamCallId.value}: ${e.getMessage}")
        }
      }

      override def onError(t: Throwable): Unit = {
        activeCalls.remove(streamCallId.value)
        try {
          val status = Status.fromThrowable(t)
          onComplete(RpcCallStatus.Failed(
            status.getCode.value(),
            Option(status.getDescription).getOrElse(status.getCode.name()),
            Option(t.getMessage)
          ))
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Bidi stream onError callback failed for call ${streamCallId.value}: ${e.getMessage}")
        }
      }

      override def onCompleted(): Unit = {
        activeCalls.remove(streamCallId.value)
        try {
          val latency = System.currentTimeMillis() - startTime
          onComplete(RpcCallStatus.Completed(0, "Bidi Stream Closed", latency))
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Bidi stream onCompleted callback failed for call ${streamCallId.value}: ${e.getMessage}")
        }
      }
    }

    val requestObserver = ClientCalls.asyncBidiStreamingCall(call, responseObserver)
    createActiveClientStream(streamCallId, call, requestObserver, method)
  }

  override def cancelCall(callId: CallId): Unit = {
    val call = activeCalls.remove(callId.value)
    if (call != null) {
      call.cancel("Cancelled by user", null)
    }
  }

  private def createActiveClientStream(
    streamCallId: CallId,
    call: ClientCall[DynamicMessage, DynamicMessage],
    requestObserver: StreamObserver[DynamicMessage],
    method: MethodSchema
  ): ActiveClientStream = {
    new ActiveClientStream {
      private val lock = new AnyRef
      private var closed = false

      override def callId: CallId = streamCallId

      override def sendNext(requestJson: String): Either[DomainError, Unit] = {
        lock.synchronized {
          if (closed) {
            Left(DomainError.RpcExecutionFailed("Client stream is already closed", Status.Code.FAILED_PRECONDITION.value()))
          } else {
            try {
              val msg = parseJsonToDynamicMessage(requestJson, method)
              requestObserver.onNext(msg)
              Right(())
            } catch {
              case NonFatal(e) =>
                Left(DomainError.PayloadParsingFailed(
                  requestJson,
                  Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                ))
            }
          }
        }
      }

      override def complete(): Unit = {
        lock.synchronized {
          if (!closed) {
            closed = true
            try {
              requestObserver.onCompleted()
            } catch {
              case NonFatal(_) => ()
            }
          }
        }
      }

      override def cancel(reason: String): Unit = {
        lock.synchronized {
          if (!closed) {
            closed = true
            activeCalls.remove(streamCallId.value)
            try {
              call.cancel(reason, null)
            } catch {
              case NonFatal(_) => ()
            }
          }
        }
      }
    }
  }

  private def withMetadataCapture[ReqT, RespT](
    underlying: ClientCall[ReqT, RespT],
    headersRef: ConcurrentHashMap[String, String],
    trailersRef: ConcurrentHashMap[String, String]
  ): ClientCall[ReqT, RespT] = {
    new ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](underlying) {
      override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit = {
        // Extend ClientCall.Listener directly to avoid Scala 3 super calls hitting package-private PartialForwardingClientCallListener.
        val capturingListener = new ClientCall.Listener[RespT] {
          override def onHeaders(responseHeaders: Metadata): Unit = {
            extractMetadata(responseHeaders, headersRef)
            responseListener.onHeaders(responseHeaders)
          }

          override def onMessage(message: RespT): Unit = {
            responseListener.onMessage(message)
          }

          override def onClose(status: Status, trailers: Metadata): Unit = {
            extractMetadata(trailers, trailersRef)
            responseListener.onClose(status, trailers)
          }

          override def onReady(): Unit = {
            responseListener.onReady()
          }
        }
        underlying.start(capturingListener, headers)
      }
    }
  }

  private def extractMetadata(metadata: Metadata, target: ConcurrentHashMap[String, String]): Unit = {
    if (metadata != null) {
      val keys = metadata.keys()
      if (keys != null) {
        keys.forEach { keyName =>
          try {
            if (keyName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
              val key = Metadata.Key.of(keyName, Metadata.BINARY_BYTE_MARSHALLER)
              val values = metadata.getAll(key)
              if (values != null) {
                target.put(keyName, values.asScala.map(Base64.getEncoder.encodeToString).mkString(", "))
              }
            } else {
              val key = Metadata.Key.of(keyName, Metadata.ASCII_STRING_MARSHALLER)
              val values = metadata.getAll(key)
              if (values != null) {
                target.put(keyName, values.asScala.mkString(", "))
              }
            }
          } catch {
            case NonFatal(_) => ()
          }
        }
      }
    }
  }

  private def buildErrorJson(status: Status, throwable: Throwable): String = {
    val detail = Option(status.getDescription)
      .orElse(Option(throwable.getMessage))
      .getOrElse("Unknown error")
    val obj = new JsonObject()
    obj.addProperty("error", s"${status.getCode}: $detail")
    gson.toJson(obj)
  }

  private def buildTypeRegistry(method: MethodSchema): Option[TypeRegistry] = {
    descriptorRegistry.typeRegistry(method.schemaHandle)
  }

  private def resolveInputDescriptor(method: MethodSchema): Descriptors.Descriptor = {
    descriptorRegistry.resolveMessage(method.schemaHandle, method.inputSchema.fullName).getOrElse {
      throw DomainError.SchemaParseError(
        method.fullMethodName,
        s"Input descriptor '${method.inputSchema.fullName}' not found in DescriptorRegistry (re-introspect or reload schema)."
      )
    }
  }

  private def resolveOutputDescriptor(method: MethodSchema): Descriptors.Descriptor = {
    descriptorRegistry.resolveMessage(method.schemaHandle, method.outputSchema.fullName).getOrElse {
      throw DomainError.SchemaParseError(
        method.fullMethodName,
        s"Output descriptor '${method.outputSchema.fullName}' not found in DescriptorRegistry (re-introspect or reload schema)."
      )
    }
  }

  private def toGrpcCallOptions(callOptions: RpcCallOptions): CallOptions = {
    var opts = CallOptions.DEFAULT
    if (callOptions.deadlineSeconds > 0) {
      opts = opts.withDeadlineAfter(callOptions.deadlineSeconds.toLong, TimeUnit.SECONDS)
    }
    if (callOptions.useGzipCompression) {
      opts = opts.withCompression("gzip")
    }
    opts
  }

  private def printMessage(message: DynamicMessage, method: MethodSchema, callOptions: RpcCallOptions): String = {
    var printer = JsonFormat.printer()
    if (callOptions.includeDefaultFields) {
      printer = printer.includingDefaultValueFields()
    }
    if (callOptions.preserveProtoFieldNames) {
      printer = printer.preservingProtoFieldNames()
    }
    val configured = buildTypeRegistry(method).map(printer.usingTypeRegistry).getOrElse(printer)
    configured.print(message)
  }

  private def parseJsonToDynamicMessage(
    json: String,
    method: MethodSchema
  ): DynamicMessage = {
    val inputDesc = resolveInputDescriptor(method)
    val builder = DynamicMessage.newBuilder(inputDesc)
    val content = if (json == null || json.trim.isEmpty) "{}" else json.trim
    val parser = buildTypeRegistry(method).map(baseParser.usingTypeRegistry).getOrElse(baseParser)
    parser.merge(content, builder)
    builder.build()
  }

  private def buildGrpcMethodDescriptor(
    method: MethodSchema,
    methodType: MethodDescriptor.MethodType
  ): MethodDescriptor[DynamicMessage, DynamicMessage] = {
    val inputDesc = resolveInputDescriptor(method)
    val outputDesc = resolveOutputDescriptor(method)
    MethodDescriptor.newBuilder[DynamicMessage, DynamicMessage]()
      .setType(methodType)
      .setFullMethodName(MethodDescriptor.generateFullMethodName(method.serviceFullName, method.name))
      .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(inputDesc)))
      .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(outputDesc)))
      .build()
  }
}
