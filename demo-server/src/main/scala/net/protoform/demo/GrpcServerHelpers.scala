package net.protoform.demo

import com.google.protobuf.{Descriptors, Duration, DynamicMessage, Timestamp}
import io.grpc.{Context, Contexts, ForwardingServerCall, Metadata, MethodDescriptor, ServerCall, ServerCallHandler, ServerInterceptor, ServerServiceDefinition, ServiceDescriptor, Status}
import io.grpc.protobuf.{ProtoFileDescriptorSupplier, ProtoUtils}
import io.grpc.stub.{ServerCalls, StreamObserver}
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

object GrpcServerHelpers {

  private val log = LoggerFactory.getLogger(getClass)

  final case class HeaderPair(key: String, value: String)

  val RequestHeadersContextKey: Context.Key[List[HeaderPair]] =
    Context.keyWithDefault("demo-request-headers", Nil)

  val RequestIdContextKey: Context.Key[String] =
    Context.keyWithDefault("demo-request-id", "")

  val MetadataAndLoggingInterceptor: ServerInterceptor = new ServerInterceptor {
    override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
    ): ServerCall.Listener[ReqT] = {
      val requestId = UUID.randomUUID().toString.take(8)
      val captured = extractHeaders(headers)
      val methodName = call.getMethodDescriptor.getFullMethodName
      log.info("[{}] -> {} (headers={})", requestId, methodName, Int.box(captured.size))

      val wrappedCall = new ForwardingServerCall.SimpleForwardingServerCall[ReqT, RespT](call) {
        override def sendHeaders(responseHeaders: Metadata): Unit = {
          responseHeaders.put(Metadata.Key.of("x-demo-server", Metadata.ASCII_STRING_MARSHALLER), "ProtoForm-Demo/1.0")
          responseHeaders.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), requestId)
          call.sendHeaders(responseHeaders)
        }

        override def close(status: Status, trailers: Metadata): Unit = {
          trailers.put(Metadata.Key.of("x-demo-trace", Metadata.ASCII_STRING_MARSHALLER), s"req-$requestId")
          trailers.put(Metadata.Key.of("x-received-header-count", Metadata.ASCII_STRING_MARSHALLER), captured.size.toString)
          log.info("[{}] <- {} status={}", requestId, methodName, status.getCode)
          call.close(status, trailers)
        }
      }

      val ctx = Context.current()
        .withValue(RequestHeadersContextKey, captured)
        .withValue(RequestIdContextKey, requestId)

      Contexts.interceptCall(ctx, wrappedCall, headers, next)
    }
  }

  private def extractHeaders(metadata: Metadata): List[HeaderPair] = {
    metadata.keys().asScala.toList.sorted.flatMap { rawKey =>
      if (rawKey.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        val key = Metadata.Key.of(rawKey, Metadata.BINARY_BYTE_MARSHALLER)
        Option(metadata.get(key)).map(bytes => HeaderPair(rawKey, Base64.getEncoder.encodeToString(bytes)))
      } else {
        val key = Metadata.Key.of(rawKey, Metadata.ASCII_STRING_MARSHALLER)
        Option(metadata.get(key)).map(v => HeaderPair(rawKey, v))
      }
    }
  }

  def nowTimestamp(): DynamicMessage = {
    val instant = Instant.now()
    val ts = Timestamp.newBuilder()
      .setSeconds(instant.getEpochSecond)
      .setNanos(instant.getNano)
      .build()
    DynamicMessage.parseFrom(Timestamp.getDescriptor, ts.toByteArray)
  }

  def durationOfMillis(millis: Long): DynamicMessage = {
    val seconds = millis / 1000L
    val nanos = ((millis % 1000L) * 1000000L).toInt
    val d = Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build()
    DynamicMessage.parseFrom(Duration.getDescriptor, d.toByteArray)
  }

  def buildHeaderEntry(key: String, value: String): DynamicMessage = {
    val desc = DemoProtoSchema.commonFd.findMessageTypeByName("HeaderEntry")
    DynamicMessage.newBuilder(desc)
      .setField(desc.findFieldByName("key"), key)
      .setField(desc.findFieldByName("value"), value)
      .build()
  }

  def buildMapEntry(entryDesc: Descriptors.Descriptor, key: String, value: String): DynamicMessage = {
    DynamicMessage.newBuilder(entryDesc)
      .setField(entryDesc.findFieldByName("key"), key)
      .setField(entryDesc.findFieldByName("value"), value)
      .build()
  }

  def toMap(msg: DynamicMessage, fieldName: String): Map[String, String] = {
    val field = msg.getDescriptorForType.findFieldByName(fieldName)
    val count = msg.getRepeatedFieldCount(field)
    (0 until count).map { idx =>
      val entry = msg.getRepeatedField(field, idx).asInstanceOf[DynamicMessage]
      val k = entry.getField(entry.getDescriptorForType.findFieldByName("key")).asInstanceOf[String]
      val v = entry.getField(entry.getDescriptorForType.findFieldByName("value")).asInstanceOf[String]
      k -> v
    }.toMap
  }

  def methodDescriptor(
    serviceDesc: Descriptors.ServiceDescriptor,
    methodName: String
  ): MethodDescriptor[DynamicMessage, DynamicMessage] = {
    val m = Option(serviceDesc.findMethodByName(methodName))
      .getOrElse(throw new IllegalArgumentException(s"Method $methodName not found in ${serviceDesc.getFullName}"))

    val methodType = (m.isClientStreaming, m.isServerStreaming) match {
      case (false, false) => MethodDescriptor.MethodType.UNARY
      case (false, true)  => MethodDescriptor.MethodType.SERVER_STREAMING
      case (true, false)  => MethodDescriptor.MethodType.CLIENT_STREAMING
      case (true, true)   => MethodDescriptor.MethodType.BIDI_STREAMING
    }

    MethodDescriptor.newBuilder[DynamicMessage, DynamicMessage]()
      .setType(methodType)
      .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceDesc.getFullName, m.getName))
      .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(m.getInputType)))
      .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(m.getOutputType)))
      .build()
  }

  final case class BoundMethod(
    descriptor: MethodDescriptor[DynamicMessage, DynamicMessage],
    handler: ServerCallHandler[DynamicMessage, DynamicMessage]
  )

  def unaryMethod(
    serviceDesc: Descriptors.ServiceDescriptor,
    methodName: String
  )(fn: DynamicMessage => Either[Status, DynamicMessage]): BoundMethod = {
    val md = methodDescriptor(serviceDesc, methodName)
    val handler = ServerCalls.asyncUnaryCall(
      new ServerCalls.UnaryMethod[DynamicMessage, DynamicMessage] {
        override def invoke(request: DynamicMessage, responseObserver: StreamObserver[DynamicMessage]): Unit = {
          try {
            fn(request) match {
              case Right(resp) =>
                responseObserver.onNext(resp)
                responseObserver.onCompleted()
              case Left(status) =>
                responseObserver.onError(status.asRuntimeException())
            }
          } catch {
            case t: Throwable =>
              responseObserver.onError(Status.INTERNAL.withDescription(t.getMessage).withCause(t).asRuntimeException())
          }
        }
      }
    )
    BoundMethod(md, handler)
  }

  def serverStreamMethod(
    serviceDesc: Descriptors.ServiceDescriptor,
    methodName: String
  )(fn: (DynamicMessage, StreamObserver[DynamicMessage]) => Unit): BoundMethod = {
    val md = methodDescriptor(serviceDesc, methodName)
    val handler = ServerCalls.asyncServerStreamingCall(
      new ServerCalls.ServerStreamingMethod[DynamicMessage, DynamicMessage] {
        override def invoke(request: DynamicMessage, responseObserver: StreamObserver[DynamicMessage]): Unit = {
          try {
            fn(request, responseObserver)
          } catch {
            case _: InterruptedException =>
              Thread.currentThread().interrupt()
              responseObserver.onError(Status.CANCELLED.withDescription("Stream interrupted").asRuntimeException())
            case t: Throwable =>
              responseObserver.onError(Status.INTERNAL.withDescription(t.getMessage).withCause(t).asRuntimeException())
          }
        }
      }
    )
    BoundMethod(md, handler)
  }

  def clientStreamMethod(
    serviceDesc: Descriptors.ServiceDescriptor,
    methodName: String
  )(fn: StreamObserver[DynamicMessage] => StreamObserver[DynamicMessage]): BoundMethod = {
    val md = methodDescriptor(serviceDesc, methodName)
    val handler = ServerCalls.asyncClientStreamingCall(
      new ServerCalls.ClientStreamingMethod[DynamicMessage, DynamicMessage] {
        override def invoke(responseObserver: StreamObserver[DynamicMessage]): StreamObserver[DynamicMessage] = {
          fn(responseObserver)
        }
      }
    )
    BoundMethod(md, handler)
  }

  def bidiStreamMethod(
    serviceDesc: Descriptors.ServiceDescriptor,
    methodName: String
  )(fn: StreamObserver[DynamicMessage] => StreamObserver[DynamicMessage]): BoundMethod = {
    val md = methodDescriptor(serviceDesc, methodName)
    val handler = ServerCalls.asyncBidiStreamingCall(
      new ServerCalls.BidiStreamingMethod[DynamicMessage, DynamicMessage] {
        override def invoke(responseObserver: StreamObserver[DynamicMessage]): StreamObserver[DynamicMessage] = {
          fn(responseObserver)
        }
      }
    )
    BoundMethod(md, handler)
  }

  def buildServiceDefinition(
    fileDescriptor: Descriptors.FileDescriptor,
    serviceDesc: Descriptors.ServiceDescriptor,
    methods: Seq[BoundMethod]
  ): ServerServiceDefinition = {
    val supplier = new ProtoFileDescriptorSupplier {
      override def getFileDescriptor: Descriptors.FileDescriptor = fileDescriptor
    }
    val sdBuilder = ServiceDescriptor.newBuilder(serviceDesc.getFullName)
      .setSchemaDescriptor(supplier)
    methods.foreach(m => sdBuilder.addMethod(m.descriptor))
    val grpcServiceDesc = sdBuilder.build()

    val defBuilder = ServerServiceDefinition.builder(grpcServiceDesc)
    methods.foreach(m => defBuilder.addMethod(m.descriptor, m.handler))
    defBuilder.build()
  }

  val ChatTurnCounter = new AtomicInteger(0)
}
