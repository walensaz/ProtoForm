package net.protoform.infrastructure.grpc

import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.{GrpcSslContexts, NettyChannelBuilder}
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory
import net.protoform.core.error.DomainError
import net.protoform.core.model.EndpointTarget
import net.protoform.ports.rpc.GrpcChannelPort

import java.io.File
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.util.control.NonFatal

class NettyGrpcChannelAdapter extends GrpcChannelPort {
  private case class ChannelKey(
    uriString: String,
    overrideAuthority: Option[String],
    tlsCertPath: Option[String],
    insecureSkipTlsVerify: Boolean,
    clientCertPath: Option[String],
    clientKeyPath: Option[String],
    maxInboundBytes: Int
  )

  private val channels = new ConcurrentHashMap[ChannelKey, ManagedChannel]()

  private def normalizeOpt(opt: Option[String]): Option[String] = {
    opt.map(_.trim).filter(_.nonEmpty)
  }

  private def cacheKey(target: EndpointTarget, maxInboundMessageMb: Int): ChannelKey = {
    val safeMb = Math.max(1, Math.min(512, maxInboundMessageMb))
    ChannelKey(
      uriString = target.uriString,
      overrideAuthority = target.overrideAuthority,
      tlsCertPath = normalizeOpt(target.tlsCertPath),
      insecureSkipTlsVerify = target.insecureSkipTlsVerify,
      clientCertPath = normalizeOpt(target.clientCertPath),
      clientKeyPath = normalizeOpt(target.clientKeyPath),
      maxInboundBytes = safeMb * 1024 * 1024
    )
  }

  override def getOrCreateChannel(target: EndpointTarget): Either[DomainError, ManagedChannel] = {
    getOrCreateChannel(target, maxInboundMessageMb = 32)
  }

  override def getOrCreateChannel(target: EndpointTarget, maxInboundMessageMb: Int): Either[DomainError, ManagedChannel] = {
    try {
      val key = cacheKey(target, maxInboundMessageMb)
      val channel = channels.compute(
        key,
        (_, existing) => {
          if (existing != null && !existing.isShutdown && !existing.isTerminated) {
            existing
          } else {
            buildChannel(target, key.maxInboundBytes)
          }
        }
      )
      Right(channel)
    } catch {
      case NonFatal(e) =>
        Left(DomainError.EndpointUnreachable(
          target,
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName),
          Some(e)
        ))
    }
  }

  private def buildChannel(target: EndpointTarget, maxInboundBytes: Int): ManagedChannel = {
    val builder = NettyChannelBuilder
      .forAddress(target.host, target.port)
      .maxInboundMessageSize(maxInboundBytes)
      .keepAliveTime(60, TimeUnit.SECONDS)
      .keepAliveTimeout(20, TimeUnit.SECONDS)

    target.overrideAuthority.foreach(builder.overrideAuthority)

    if (target.useTls) {
      val caOpt = normalizeOpt(target.tlsCertPath)
      val certOpt = normalizeOpt(target.clientCertPath)
      val keyOpt = normalizeOpt(target.clientKeyPath)

      val hasClientPair = (certOpt, keyOpt) match {
        case (Some(_), Some(_)) => true
        case (None, None)       => false
        case _ =>
          throw new IllegalArgumentException(
            "mTLS requires both a client certificate (.pem/.crt) and a client private key (.pem/.key)."
          )
      }

      if (!target.insecureSkipTlsVerify && caOpt.isEmpty && !hasClientPair) {
        builder.useTransportSecurity()
      } else {
        val sslBuilder = GrpcSslContexts.forClient()
        if (target.insecureSkipTlsVerify) {
          sslBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE)
        } else {
          caOpt.foreach(p => sslBuilder.trustManager(requireReadableFile(p, "CA certificate")))
        }
        if (hasClientPair) {
          val certFile = requireReadableFile(certOpt.get, "client certificate")
          val keyFile = requireReadableFile(keyOpt.get, "client private key")
          try {
            sslBuilder.keyManager(certFile, keyFile)
          } catch {
            case NonFatal(e) =>
              throw new IllegalArgumentException(
                s"Failed to load mTLS client certificate/key (ensure private key is unencrypted PKCS#8 PEM): ${e.getMessage}",
                e
              )
          }
        }
        builder.sslContext(sslBuilder.build())
      }
    } else {
      builder.usePlaintext()
    }

    builder.build()
  }

  private def requireReadableFile(path: String, label: String): File = {
    val file = new File(path)
    if (!file.exists() || !file.isFile) {
      throw new IllegalArgumentException(s"Configured $label file does not exist: $path")
    }
    file
  }

  override def shutdownChannel(target: EndpointTarget): Unit = {
    val caOpt = normalizeOpt(target.tlsCertPath)
    val clientCertOpt = normalizeOpt(target.clientCertPath)
    val clientKeyOpt = normalizeOpt(target.clientKeyPath)
    val snapshot = channels.entrySet().toArray(Array.empty[java.util.Map.Entry[ChannelKey, ManagedChannel]])
    snapshot.foreach { entry =>
      val k = entry.getKey
      if (
        k.uriString == target.uriString &&
        k.overrideAuthority == target.overrideAuthority &&
        k.tlsCertPath == caOpt &&
        k.clientCertPath == clientCertOpt &&
        k.clientKeyPath == clientKeyOpt
      ) {
        val removed = channels.remove(k)
        if (removed != null) {
          awaitShutdown(removed)
        }
      }
    }
  }

  override def shutdownAll(): Unit = {
    val snapshot = channels.values().toArray(Array.empty[ManagedChannel])
    channels.clear()
    snapshot.foreach { channel =>
      try {
        channel.shutdown()
      } catch {
        case NonFatal(_) => ()
      }
    }
    snapshot.foreach(awaitTerminationOrForce)
  }

  private def awaitShutdown(channel: ManagedChannel): Unit = {
    try {
      channel.shutdown()
    } catch {
      case NonFatal(_) => ()
    }
    awaitTerminationOrForce(channel)
  }

  private def awaitTerminationOrForce(channel: ManagedChannel): Unit = {
    try {
      if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
        channel.shutdownNow()
      }
    } catch {
      case _: InterruptedException =>
        channel.shutdownNow()
        Thread.currentThread().interrupt()
      case NonFatal(_) =>
        try {
          channel.shutdownNow()
        } catch {
          case NonFatal(_) => ()
        }
    }
  }
}
