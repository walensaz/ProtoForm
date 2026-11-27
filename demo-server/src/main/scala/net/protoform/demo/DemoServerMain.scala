package net.protoform.demo

import io.grpc.{MethodDescriptor, Server, ServerInterceptors, ServerServiceDefinition}
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.{HealthStatusManager, ProtoReflectionService}
import io.grpc.reflection.v1alpha
import io.grpc.stub.{ServerCalls, StreamObserver}

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}

final case class DemoServerConfig(
  host: String = "127.0.0.1",
  port: Int = 50051,
  reflectionEnabled: Boolean = true,
  healthEnabled: Boolean = true,
  tlsCertPath: Option[Path] = None,
  tlsKeyPath: Option[Path] = None,
  writeDescPath: Option[Path] = None
)

final class DemoGrpcServer(config: DemoServerConfig = DemoServerConfig()) {

  private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
    4,
    new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "protoform-demo-worker")
        t.setDaemon(true)
        t
      }
    }
  )

  private val healthManager = new HealthStatusManager()
  private val sandboxService = new SandboxServiceImpl(scheduler)
  private val orderService = new OrderServiceImpl(scheduler)
  private var server: Option[Server] = None

  def port: Int = server.map(_.getPort).getOrElse(config.port)

  def start(): DemoGrpcServer = {
    if (server.isDefined) return this

    val builder = NettyServerBuilder.forAddress(new InetSocketAddress(config.host, config.port))

    for {
      certPath <- config.tlsCertPath
      keyPath <- config.tlsKeyPath
    } {
      builder.useTransportSecurity(certPath.toFile, keyPath.toFile)
    }

    builder.addService(ServerInterceptors.intercept(sandboxService.serviceDefinition, GrpcServerHelpers.MetadataAndLoggingInterceptor))
    builder.addService(ServerInterceptors.intercept(orderService.serviceDefinition, GrpcServerHelpers.MetadataAndLoggingInterceptor))

    if (config.healthEnabled) {
      builder.addService(healthManager.getHealthService)
      healthManager.setStatus("", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING)
      healthManager.setStatus("demo.sandbox.v1.SandboxService", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING)
      healthManager.setStatus("demo.orders.v1.OrderService", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING)
    }

    if (config.reflectionEnabled) {
      val v1AlphaReflection = ProtoReflectionService.newInstance()
      builder.addService(v1AlphaReflection)
      builder.addService(buildV1ReflectionAlias(v1AlphaReflection))
    }

    val started = builder.build().start()
    server = Some(started)
    this
  }

  private def buildV1ReflectionAlias(v1AlphaService: io.grpc.BindableService): ServerServiceDefinition = {
    val v1Method: MethodDescriptor[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse] =
      v1alpha.ServerReflectionGrpc.getServerReflectionInfoMethod()
        .toBuilder()
        .setFullMethodName("grpc.reflection.v1.ServerReflection/ServerReflectionInfo")
        .build()

    val impl = v1AlphaService.asInstanceOf[v1alpha.ServerReflectionGrpc.ServerReflectionImplBase]
    ServerServiceDefinition.builder("grpc.reflection.v1.ServerReflection")
      .addMethod(
        v1Method,
        ServerCalls.asyncBidiStreamingCall(
          new ServerCalls.BidiStreamingMethod[v1alpha.ServerReflectionRequest, v1alpha.ServerReflectionResponse] {
            override def invoke(
              responseObserver: StreamObserver[v1alpha.ServerReflectionResponse]
            ): StreamObserver[v1alpha.ServerReflectionRequest] = {
              impl.serverReflectionInfo(responseObserver)
            }
          }
        )
      )
      .build()
  }

  def shutdown(): Unit = {
    healthManager.enterTerminalState()
    server.foreach { s =>
      s.shutdown()
      if (!s.awaitTermination(3, TimeUnit.SECONDS)) {
        s.shutdownNow()
      }
    }
    server = None
    scheduler.shutdownNow()
  }

  def awaitTermination(): Unit = {
    server.foreach(_.awaitTermination())
  }
}

object DemoServerMain {

  def parseArgs(args: Array[String]): DemoServerConfig = {
    var cfg = DemoServerConfig()
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--host" if i + 1 < args.length =>
          cfg = cfg.copy(host = args(i + 1)); i += 2
        case "--port" if i + 1 < args.length =>
          cfg = cfg.copy(port = args(i + 1).toInt); i += 2
        case "--no-reflection" =>
          cfg = cfg.copy(reflectionEnabled = false); i += 1
        case "--no-health" =>
          cfg = cfg.copy(healthEnabled = false); i += 1
        case "--cert" if i + 1 < args.length =>
          cfg = cfg.copy(tlsCertPath = Some(Path.of(args(i + 1)))); i += 2
        case "--key" if i + 1 < args.length =>
          cfg = cfg.copy(tlsKeyPath = Some(Path.of(args(i + 1)))); i += 2
        case "--write-desc" if i + 1 < args.length =>
          cfg = cfg.copy(writeDescPath = Some(Path.of(args(i + 1)))); i += 2
        case other =>
          throw new IllegalArgumentException(s"Unknown or incomplete flag: $other")
      }
    }
    cfg
  }

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args)

    config.writeDescPath match {
      case Some(outPath) =>
        DemoProtoSchema.writeFileDescriptorSet(outPath)
        println(s"Wrote FileDescriptorSet to ${outPath.toAbsolutePath}")
      case None =>
        runServer(config)
    }
  }

  private def runServer(config: DemoServerConfig): Unit = {
    val demoServer = new DemoGrpcServer(config).start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("\nShutting down ProtoForm Demo Server...")
      demoServer.shutdown()
    }))

    val tlsLabel = if (config.tlsCertPath.isDefined && config.tlsKeyPath.isDefined) "TLS enabled" else "plaintext"
    println("==================================================================")
    println(s" ProtoForm Demo gRPC Server listening on ${config.host}:${demoServer.port} ($tlsLabel)")
    println("==================================================================")
    println(s" Reflection : ${if (config.reflectionEnabled) "enabled (v1 & v1alpha)" else "disabled"}")
    println(s" Health     : ${if (config.healthEnabled) "enabled (grpc.health.v1.Health)" else "disabled"}")
    println(" Services   :")
    println("   - demo.sandbox.v1.SandboxService")
    println("       Unary        : Echo, SimulateBehavior")
    println("       ServerStream : StreamEvents")
    println("       ClientStream : CollectMetrics")
    println("       BidiStream   : InteractiveChat (commands: /ping /count /time /fail)")
    println("   - demo.orders.v1.OrderService")
    println("       Unary        : CreateOrder, GetOrder, ListOrders, GetAdminAudit")
    println("       ServerStream : WatchOrderUpdates")
    println(" Seeded IDs : ord-1001, ord-1002")
    println(s" Auth Demo  : Bearer '${OrderServiceImpl.DemoBearerToken}' OR x-api-key '${OrderServiceImpl.DemoApiKey}'")
    println(" Press Ctrl+C to stop.")
    println("==================================================================")

    demoServer.awaitTermination()
  }
}
