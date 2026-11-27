package net.protoform.infrastructure.auth

import io.grpc.{CallOptions, Channel, ClientCall, ClientInterceptor, ForwardingClientCall, Metadata, MethodDescriptor}
import net.protoform.core.auth.MetadataHeader
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.{Base64, Locale}

class MetadataAuthInterceptor(headers: List[MetadataHeader]) extends ClientInterceptor {
  private val logger = LoggerFactory.getLogger(getClass)

  override def interceptCall[ReqT, RespT](
    method: MethodDescriptor[ReqT, RespT],
    callOptions: CallOptions,
    next: Channel
  ): ClientCall[ReqT, RespT] = {
    new ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](next.newCall(method, callOptions)) {
      override def start(responseListener: ClientCall.Listener[RespT], metadata: Metadata): Unit = {
        headers.filter(_.enabled).foreach { h =>
          val keyName = Option(h.key).getOrElse("").trim.toLowerCase(Locale.ROOT)
          val keyVal = Option(h.value).getOrElse("").trim
          if (keyName.nonEmpty) {
            try {
              if (keyName.endsWith("-bin")) {
                val key = Metadata.Key.of(keyName, Metadata.BINARY_BYTE_MARSHALLER)
                val bytes = try {
                  Base64.getDecoder.decode(keyVal)
                } catch {
                  case _: IllegalArgumentException =>
                    keyVal.getBytes(StandardCharsets.UTF_8)
                }
                metadata.put(key, bytes)
              } else {
                val key = Metadata.Key.of(keyName, Metadata.ASCII_STRING_MARSHALLER)
                metadata.put(key, keyVal)
              }
            } catch {
              case e: IllegalArgumentException =>
                logger.warn(s"Skipping invalid gRPC metadata header '$keyName': ${e.getMessage}")
            }
          }
        }
        super.start(responseListener, metadata)
      }
    }
  }
}
