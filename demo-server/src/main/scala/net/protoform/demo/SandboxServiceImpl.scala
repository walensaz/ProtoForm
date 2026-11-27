package net.protoform.demo

import com.google.protobuf.{ByteString, Descriptors, DynamicMessage, Struct, Timestamp}
import com.google.protobuf.util.JsonFormat
import io.grpc.{Context, ServerServiceDefinition, Status}
import io.grpc.stub.StreamObserver

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters._

final class SandboxServiceImpl(scheduler: ScheduledExecutorService) {

  import GrpcServerHelpers._

  private val fd = DemoProtoSchema.sandboxFd
  private val serviceDesc = fd.findServiceByName("SandboxService")

  private val echoRespDesc = fd.findMessageTypeByName("EchoResponse")
  private val behaviorRespDesc = fd.findMessageTypeByName("BehaviorResponse")
  private val serverEventDesc = fd.findMessageTypeByName("ServerEvent")
  private val metricSummaryDesc = fd.findMessageTypeByName("MetricSummary")
  private val chatMsgDesc = fd.findMessageTypeByName("ChatMessage")
  private val priorityEnum = DemoProtoSchema.commonFd.findEnumTypeByName("Priority")

  val serviceDefinition: ServerServiceDefinition = buildServiceDefinition(
    fd,
    serviceDesc,
    Seq(
      unaryMethod(serviceDesc, "Echo")(handleEcho),
      unaryMethod(serviceDesc, "SimulateBehavior")(handleSimulateBehavior),
      serverStreamMethod(serviceDesc, "StreamEvents")(handleStreamEvents),
      clientStreamMethod(serviceDesc, "CollectMetrics")(handleCollectMetrics),
      bidiStreamMethod(serviceDesc, "InteractiveChat")(handleInteractiveChat)
    )
  )

  private def handleEcho(req: DynamicMessage): Either[Status, DynamicMessage] = {
    val startNanos = System.nanoTime()
    val reqDesc = req.getDescriptorForType

    val rawBytes = req.getField(reqDesc.findFieldByName("raw_payload")).asInstanceOf[ByteString]
    val tags = req.getField(reqDesc.findFieldByName("tags")).asInstanceOf[java.util.List[String]].asScala.toList
    val attributes = toMap(req, "attributes")

    val detailSummary = {
      val oneof = reqDesc.getOneofs.get(0)
      Option(req.getOneofFieldDescriptor(oneof)) match {
        case None => "none"
        case Some(f) if f.getName == "note" => s"note=${req.getField(f)}"
        case Some(f) if f.getName == "code" => s"code=${req.getField(f)}"
        case Some(f) if f.getName == "extra" =>
          val structMsg = req.getField(f).asInstanceOf[DynamicMessage]
          s"extra=${JsonFormat.printer().omittingInsignificantWhitespace().print(structMsg)}"
        case Some(f) => f.getName
      }
    }

    val builder = DynamicMessage.newBuilder(echoRespDesc)
      .setField(echoRespDesc.findFieldByName("message"), req.getField(reqDesc.findFieldByName("message")))
      .setField(echoRespDesc.findFieldByName("count"), req.getField(reqDesc.findFieldByName("count")))
      .setField(echoRespDesc.findFieldByName("score"), req.getField(reqDesc.findFieldByName("score")))
      .setField(echoRespDesc.findFieldByName("active"), req.getField(reqDesc.findFieldByName("active")))
      .setField(echoRespDesc.findFieldByName("raw_payload_bytes"), Int.box(rawBytes.size()))
      .setField(echoRespDesc.findFieldByName("priority"), req.getField(reqDesc.findFieldByName("priority")))
      .setField(echoRespDesc.findFieldByName("received_at"), nowTimestamp())
      .setField(echoRespDesc.findFieldByName("detail_summary"), detailSummary)

    val clientField = reqDesc.findFieldByName("client")
    if (req.hasField(clientField)) {
      builder.setField(echoRespDesc.findFieldByName("client"), req.getField(clientField))
    }

    val tagsRespField = echoRespDesc.findFieldByName("tags")
    tags.foreach(t => builder.addRepeatedField(tagsRespField, t))

    val attrsRespField = echoRespDesc.findFieldByName("attributes")
    val attrsEntryDesc = attrsRespField.getMessageType
    attributes.toSeq.sortBy(_._1).foreach { case (k, v) =>
      builder.addRepeatedField(attrsRespField, buildMapEntry(attrsEntryDesc, k, v))
    }

    val headersRespField = echoRespDesc.findFieldByName("received_headers")
    RequestHeadersContextKey.get().foreach { h =>
      builder.addRepeatedField(headersRespField, buildHeaderEntry(h.key, h.value))
    }

    val elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1000000L)
    builder.setField(echoRespDesc.findFieldByName("server_elapsed"), durationOfMillis(elapsedMs))
    Right(builder.build())
  }

  private def handleSimulateBehavior(req: DynamicMessage): Either[Status, DynamicMessage] = {
    val reqDesc = req.getDescriptorForType
    val statusVal = req.getField(reqDesc.findFieldByName("status_code")).asInstanceOf[Descriptors.EnumValueDescriptor]
    val rawDelay = req.getField(reqDesc.findFieldByName("delay_ms")).asInstanceOf[Int]
    val delayMs = Math.max(0, Math.min(rawDelay, 30000))
    val errorMsg = req.getField(reqDesc.findFieldByName("error_message")).asInstanceOf[String]
    val detailHint = req.getField(reqDesc.findFieldByName("detail_hint")).asInstanceOf[String]

    if (delayMs > 0) {
      Thread.sleep(delayMs.toLong)
    }

    if (Context.current().isCancelled) {
      return Left(Status.CANCELLED.withDescription("Client cancelled before completion"))
    }

    val mappedStatus: Option[Status] = statusVal.getName match {
      case "GRPC_STATUS_CODE_UNSPECIFIED" | "GRPC_STATUS_CODE_OK" => None
      case "GRPC_STATUS_CODE_INVALID_ARGUMENT"  => Some(Status.INVALID_ARGUMENT)
      case "GRPC_STATUS_CODE_NOT_FOUND"         => Some(Status.NOT_FOUND)
      case "GRPC_STATUS_CODE_PERMISSION_DENIED" => Some(Status.PERMISSION_DENIED)
      case "GRPC_STATUS_CODE_UNAUTHENTICATED"   => Some(Status.UNAUTHENTICATED)
      case "GRPC_STATUS_CODE_RESOURCE_EXHAUSTED" => Some(Status.RESOURCE_EXHAUSTED)
      case "GRPC_STATUS_CODE_UNAVAILABLE"       => Some(Status.UNAVAILABLE)
      case "GRPC_STATUS_CODE_INTERNAL"          => Some(Status.INTERNAL)
      case _                                    => Some(Status.UNKNOWN)
    }

    mappedStatus match {
      case Some(status) =>
        val desc = if (errorMsg.nonEmpty) errorMsg else s"Simulated ${status.getCode.name()} failure"
        val fullDesc = if (detailHint.nonEmpty) s"$desc (hint: $detailHint)" else desc
        Left(status.withDescription(fullDesc))
      case None =>
        val note = if (detailHint.nonEmpty) detailHint else "Simulated OK response"
        val resp = DynamicMessage.newBuilder(behaviorRespDesc)
          .setField(behaviorRespDesc.findFieldByName("ok"), java.lang.Boolean.TRUE)
          .setField(behaviorRespDesc.findFieldByName("status_name"), "OK")
          .setField(behaviorRespDesc.findFieldByName("delayed_ms"), Int.box(delayMs))
          .setField(behaviorRespDesc.findFieldByName("note"), note)
          .setField(behaviorRespDesc.findFieldByName("completed_at"), nowTimestamp())
          .build()
        Right(resp)
    }
  }

  private def handleStreamEvents(req: DynamicMessage, observer: StreamObserver[DynamicMessage]): Unit = {
    val reqDesc = req.getDescriptorForType
    val topicRaw = req.getField(reqDesc.findFieldByName("topic")).asInstanceOf[String]
    val topic = if (topicRaw.trim.isEmpty) "system.heartbeat" else topicRaw.trim
    val rawCount = req.getField(reqDesc.findFieldByName("count")).asInstanceOf[Int]
    val count = if (rawCount <= 0) 5 else Math.min(rawCount, 50)
    val rawInterval = req.getField(reqDesc.findFieldByName("interval_ms")).asInstanceOf[Int]
    val intervalMs = if (rawInterval <= 0) 250 else Math.min(rawInterval, 5000)
    val minPriority = req.getField(reqDesc.findFieldByName("min_priority")).asInstanceOf[Descriptors.EnumValueDescriptor]
    val ctx = Context.current()
    val startNanos = System.nanoTime()

    def scheduleNext(seq: Int): Unit = {
      if (ctx.isCancelled) {
        return
      }
      if (seq > count) {
        observer.onCompleted()
        return
      }

      val priority = if (minPriority.getNumber == 0) {
        priorityEnum.findValueByNumber(((seq - 1) % 4) + 1)
      } else {
        minPriority
      }
      val elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1000000L)
      val metaField = serverEventDesc.findFieldByName("metadata")
      val metaEntryDesc = metaField.getMessageType

      val event = DynamicMessage.newBuilder(serverEventDesc)
        .setField(serverEventDesc.findFieldByName("sequence"), Int.box(seq))
        .setField(serverEventDesc.findFieldByName("topic"), topic)
        .setField(serverEventDesc.findFieldByName("priority"), priority)
        .setField(serverEventDesc.findFieldByName("payload"), s"Event #$seq on '$topic'")
        .setField(serverEventDesc.findFieldByName("emitted_at"), nowTimestamp())
        .setField(serverEventDesc.findFieldByName("elapsed"), durationOfMillis(elapsedMs))
        .addRepeatedField(metaField, buildMapEntry(metaEntryDesc, "source", "sandbox-stream"))
        .addRepeatedField(metaField, buildMapEntry(metaEntryDesc, "progress", s"$seq/$count"))
        .build()

      try {
        observer.onNext(event)
      } catch {
        case _: Throwable => return
      }

      if (seq == count) {
        observer.onCompleted()
      } else {
        scheduler.schedule(
          new Runnable {
            override def run(): Unit = scheduleNext(seq + 1)
          },
          intervalMs.toLong,
          TimeUnit.MILLISECONDS
        )
      }
    }

    scheduler.execute(new Runnable {
      override def run(): Unit = scheduleNext(1)
    })
  }

  private def handleCollectMetrics(responseObserver: StreamObserver[DynamicMessage]): StreamObserver[DynamicMessage] = {
    new StreamObserver[DynamicMessage] {
      private val lock = new Object()
      private var count = 0
      private var sum = 0.0
      private var min = Double.PositiveInfinity
      private var max = Double.NegativeInfinity
      private val metrics = mutable.LinkedHashSet[String]()

      override def onNext(point: DynamicMessage): Unit = lock.synchronized {
        val d = point.getDescriptorForType
        val name = point.getField(d.findFieldByName("metric_name")).asInstanceOf[String]
        val value = point.getField(d.findFieldByName("value")).asInstanceOf[Double]
        count += 1
        sum += value
        if (value < min) min = value
        if (value > max) max = value
        if (name.nonEmpty) metrics += name
      }

      override def onError(t: Throwable): Unit = ()

      override def onCompleted(): Unit = lock.synchronized {
        val avg = if (count == 0) 0.0 else sum / count.toDouble
        val effMin = if (count == 0) 0.0 else min
        val effMax = if (count == 0) 0.0 else max
        val builder = DynamicMessage.newBuilder(metricSummaryDesc)
          .setField(metricSummaryDesc.findFieldByName("count"), Int.box(count))
          .setField(metricSummaryDesc.findFieldByName("sum"), Double.box(sum))
          .setField(metricSummaryDesc.findFieldByName("min"), Double.box(effMin))
          .setField(metricSummaryDesc.findFieldByName("max"), Double.box(effMax))
          .setField(metricSummaryDesc.findFieldByName("average"), Double.box(avg))

        val distinctField = metricSummaryDesc.findFieldByName("distinct_metrics")
        metrics.foreach(m => builder.addRepeatedField(distinctField, m))

        responseObserver.onNext(builder.build())
        responseObserver.onCompleted()
      }
    }
  }

  private def handleInteractiveChat(responseObserver: StreamObserver[DynamicMessage]): StreamObserver[DynamicMessage] = {
    val msgCount = new AtomicInteger(0)
    new StreamObserver[DynamicMessage] {
      override def onNext(msg: DynamicMessage): Unit = {
        val d = msg.getDescriptorForType
        val sender = Option(msg.getField(d.findFieldByName("sender")).asInstanceOf[String]).filter(_.nonEmpty).getOrElse("client")
        val body = Option(msg.getField(d.findFieldByName("body")).asInstanceOf[String]).getOrElse("")
        val mood = Option(msg.getField(d.findFieldByName("mood")).asInstanceOf[String]).filter(_.nonEmpty).getOrElse("neutral")
        val n = msgCount.incrementAndGet()

        if (body.trim == "/fail") {
          responseObserver.onError(Status.ABORTED.withDescription("Chat aborted via /fail command").asRuntimeException())
          return
        }

        val replyBody = body.trim match {
          case "/ping"  => "pong"
          case "/count" => s"Received $n message(s) in this session"
          case "/time"  => s"Server epoch millis: ${System.currentTimeMillis()}"
          case other    => s"[$sender ($mood)] echoed (#$n): $other"
        }

        val reply = DynamicMessage.newBuilder(chatMsgDesc)
          .setField(chatMsgDesc.findFieldByName("sender"), "demo-bot")
          .setField(chatMsgDesc.findFieldByName("body"), replyBody)
          .setField(chatMsgDesc.findFieldByName("sent_at"), nowTimestamp())
          .setField(chatMsgDesc.findFieldByName("mood"), "helpful")
          .build()

        responseObserver.onNext(reply)
      }

      override def onError(t: Throwable): Unit = ()

      override def onCompleted(): Unit = {
        responseObserver.onCompleted()
      }
    }
  }
}
