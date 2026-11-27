package net.protoform.infrastructure

import net.protoform.core.model.EndpointTarget
import net.protoform.infrastructure.grpc.NettyGrpcChannelAdapter
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NettyGrpcChannelAdapterSpec extends AnyFunSuite with Matchers {

  test("reuses cached channel for identical target") {
    val adapter = new NettyGrpcChannelAdapter()
    try {
      val target = EndpointTarget(name = "local", host = "localhost", port = 50051, useTls = false)
      val ch1 = adapter.getOrCreateChannel(target).toOption.get
      val ch2 = adapter.getOrCreateChannel(target).toOption.get
      ch1 shouldBe theSameInstanceAs(ch2)
    } finally {
      adapter.shutdownAll()
    }
  }

  test("isolates cached channels when overrideAuthority differs") {
    val adapter = new NettyGrpcChannelAdapter()
    try {
      val t1 = EndpointTarget(name = "a", host = "localhost", port = 50051, useTls = false, overrideAuthority = Some("svc-a.local"))
      val t2 = EndpointTarget(name = "b", host = "localhost", port = 50051, useTls = false, overrideAuthority = Some("svc-b.local"))
      val ch1 = adapter.getOrCreateChannel(t1).toOption.get
      val ch2 = adapter.getOrCreateChannel(t2).toOption.get
      ch1 should not be theSameInstanceAs(ch2)
      ch1.authority() shouldBe "svc-a.local"
      ch2.authority() shouldBe "svc-b.local"
    } finally {
      adapter.shutdownAll()
    }
  }

  test("isolates cached channels when tlsCertPath differs") {
    val adapter = new NettyGrpcChannelAdapter()
    try {
      val t1 = EndpointTarget(name = "c1", host = "localhost", port = 50051, useTls = false, tlsCertPath = Some("/certs/ca-1.pem"))
      val t2 = EndpointTarget(name = "c2", host = "localhost", port = 50051, useTls = false, tlsCertPath = Some("/certs/ca-2.pem"))
      val ch1 = adapter.getOrCreateChannel(t1).toOption.get
      val ch2 = adapter.getOrCreateChannel(t2).toOption.get
      ch1 should not be theSameInstanceAs(ch2)
    } finally {
      adapter.shutdownAll()
    }
  }

  test("shutdownAll terminates all cached channels and recreates on subsequent request") {
    val adapter = new NettyGrpcChannelAdapter()
    try {
      val t1 = EndpointTarget(name = "s1", host = "localhost", port = 50051, useTls = false)
      val t2 = EndpointTarget(name = "s2", host = "localhost", port = 50052, useTls = false)
      val ch1 = adapter.getOrCreateChannel(t1).toOption.get
      val ch2 = adapter.getOrCreateChannel(t2).toOption.get

      adapter.shutdownAll()
      ch1.isShutdown shouldBe true
      ch2.isShutdown shouldBe true

      val ch1Recreated = adapter.getOrCreateChannel(t1).toOption.get
      ch1Recreated should not be theSameInstanceAs(ch1)
      ch1Recreated.isShutdown shouldBe false
    } finally {
      adapter.shutdownAll()
    }
  }

  test("supports dev insecureSkipTlsVerify and isolates channels by maxInboundMessageMb") {
    val adapter = new NettyGrpcChannelAdapter()
    try {
      val t1 = EndpointTarget(name = "tls-insecure", host = "localhost", port = 50051, useTls = true, insecureSkipTlsVerify = true)
      val t2 = EndpointTarget(name = "tls-strict", host = "localhost", port = 50051, useTls = true, insecureSkipTlsVerify = false)
      val ch1 = adapter.getOrCreateChannel(t1, maxInboundMessageMb = 16).toOption.get
      val ch2 = adapter.getOrCreateChannel(t1, maxInboundMessageMb = 64).toOption.get
      val ch3 = adapter.getOrCreateChannel(t2, maxInboundMessageMb = 16).toOption.get

      ch1 should not be theSameInstanceAs(ch2)
      ch1 should not be theSameInstanceAs(ch3)
    } finally {
      adapter.shutdownAll()
    }
  }

  test("isolates cached channels when mTLS client cert/key differ and rejects half-configured mTLS") {
    val adapter = new NettyGrpcChannelAdapter()
    try {
      val t1 = EndpointTarget(
        name = "m1",
        host = "localhost",
        port = 50051,
        useTls = false,
        clientCertPath = Some("/certs/c1.pem"),
        clientKeyPath = Some("/certs/k1.pem")
      )
      val t2 = EndpointTarget(
        name = "m2",
        host = "localhost",
        port = 50051,
        useTls = false,
        clientCertPath = Some("/certs/c2.pem"),
        clientKeyPath = Some("/certs/k2.pem")
      )
      val ch1 = adapter.getOrCreateChannel(t1).toOption.get
      val ch2 = adapter.getOrCreateChannel(t2).toOption.get
      ch1 should not be theSameInstanceAs(ch2)

      val halfConfigured = EndpointTarget(
        name = "half",
        host = "localhost",
        port = 50051,
        useTls = true,
        clientCertPath = Some("/certs/only-cert.pem"),
        clientKeyPath = None
      )
      val err = adapter.getOrCreateChannel(halfConfigured)
      err.isLeft shouldBe true
      err.left.toOption.get.message should include("mTLS requires both")
    } finally {
      adapter.shutdownAll()
    }
  }
}
