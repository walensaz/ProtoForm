package net.protoform.demo

import com.google.protobuf.{Descriptors, DynamicMessage}
import io.grpc.{Context, ServerServiceDefinition, Status}
import io.grpc.stub.StreamObserver

import java.util.concurrent.{ConcurrentHashMap, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

object OrderServiceImpl {
  val DemoBearerToken = "demo-secret-token"
  val DemoApiKey = "demo-api-key"
}

final class OrderServiceImpl(scheduler: ScheduledExecutorService) {

  import GrpcServerHelpers._
  import OrderServiceImpl._

  private val fd = DemoProtoSchema.ordersFd
  private val serviceDesc = fd.findServiceByName("OrderService")

  private val orderDesc = fd.findMessageTypeByName("Order")
  private val orderItemDesc = fd.findMessageTypeByName("OrderItem")
  private val listOrdersRespDesc = fd.findMessageTypeByName("ListOrdersResponse")
  private val adminAuditRespDesc = fd.findMessageTypeByName("AdminAuditResponse")
  private val orderUpdateDesc = fd.findMessageTypeByName("OrderUpdate")
  private val orderStatusEnum = fd.findEnumTypeByName("OrderStatus")
  private val moneyDesc = DemoProtoSchema.commonFd.findMessageTypeByName("Money")
  private val priorityEnum = DemoProtoSchema.commonFd.findEnumTypeByName("Priority")

  private val sequence = new AtomicInteger(1002)
  private val orders = new ConcurrentHashMap[String, DynamicMessage]()

  seedSampleOrders()

  val serviceDefinition: ServerServiceDefinition = buildServiceDefinition(
    fd,
    serviceDesc,
    Seq(
      unaryMethod(serviceDesc, "CreateOrder")(handleCreateOrder),
      unaryMethod(serviceDesc, "GetOrder")(handleGetOrder),
      unaryMethod(serviceDesc, "ListOrders")(handleListOrders),
      unaryMethod(serviceDesc, "GetAdminAudit")(handleGetAdminAudit),
      serverStreamMethod(serviceDesc, "WatchOrderUpdates")(handleWatchOrderUpdates)
    )
  )

  private def buildMoney(currency: String, units: Long, nanos: Int): DynamicMessage = {
    DynamicMessage.newBuilder(moneyDesc)
      .setField(moneyDesc.findFieldByName("currency"), currency)
      .setField(moneyDesc.findFieldByName("units"), Long.box(units))
      .setField(moneyDesc.findFieldByName("nanos"), Int.box(nanos))
      .build()
  }

  private def buildItem(sku: String, name: String, qty: Int, currency: String, units: Long, nanos: Int): DynamicMessage = {
    DynamicMessage.newBuilder(orderItemDesc)
      .setField(orderItemDesc.findFieldByName("sku"), sku)
      .setField(orderItemDesc.findFieldByName("name"), name)
      .setField(orderItemDesc.findFieldByName("quantity"), Int.box(qty))
      .setField(orderItemDesc.findFieldByName("unit_price"), buildMoney(currency, units, nanos))
      .build()
  }

  private def seedSampleOrders(): Unit = {
    val labelsField = orderDesc.findFieldByName("labels")
    val labelsEntry = labelsField.getMessageType

    val o1 = DynamicMessage.newBuilder(orderDesc)
      .setField(orderDesc.findFieldByName("order_id"), "ord-1001")
      .setField(orderDesc.findFieldByName("customer_id"), "cust-acme")
      .setField(orderDesc.findFieldByName("status"), orderStatusEnum.findValueByName("ORDER_STATUS_CONFIRMED"))
      .addRepeatedField(orderDesc.findFieldByName("items"), buildItem("SKU-GRPC-01", "ProtoForm Pro License", 2, "USD", 49L, 0))
      .setField(orderDesc.findFieldByName("total"), buildMoney("USD", 98L, 0))
      .setField(orderDesc.findFieldByName("priority"), priorityEnum.findValueByName("PRIORITY_HIGH"))
      .addRepeatedField(labelsField, buildMapEntry(labelsEntry, "region", "us-central"))
      .setField(orderDesc.findFieldByName("created_at"), nowTimestamp())
      .build()

    val o2 = DynamicMessage.newBuilder(orderDesc)
      .setField(orderDesc.findFieldByName("order_id"), "ord-1002")
      .setField(orderDesc.findFieldByName("customer_id"), "cust-beta")
      .setField(orderDesc.findFieldByName("status"), orderStatusEnum.findValueByName("ORDER_STATUS_SHIPPED"))
      .addRepeatedField(orderDesc.findFieldByName("items"), buildItem("SKU-PROTO-99", "Protobuf Schema Kit", 1, "USD", 19L, 500000000))
      .setField(orderDesc.findFieldByName("total"), buildMoney("USD", 19L, 500000000))
      .setField(orderDesc.findFieldByName("priority"), priorityEnum.findValueByName("PRIORITY_MEDIUM"))
      .addRepeatedField(labelsField, buildMapEntry(labelsEntry, "tier", "starter"))
      .setField(orderDesc.findFieldByName("created_at"), nowTimestamp())
      .build()

    orders.put("ord-1001", o1)
    orders.put("ord-1002", o2)
  }

  private def handleCreateOrder(req: DynamicMessage): Either[Status, DynamicMessage] = {
    val reqDesc = req.getDescriptorForType
    val customerId = req.getField(reqDesc.findFieldByName("customer_id")).asInstanceOf[String].trim
    val items = req.getField(reqDesc.findFieldByName("items")).asInstanceOf[java.util.List[DynamicMessage]].asScala.toList

    if (customerId.isEmpty) {
      return Left(Status.INVALID_ARGUMENT.withDescription("customer_id must not be empty"))
    }
    if (items.isEmpty) {
      return Left(Status.INVALID_ARGUMENT.withDescription("items must contain at least one OrderItem"))
    }

    var totalNanosSum = 0L
    var currency = "USD"
    items.foreach { item =>
      val qty = Math.max(1, item.getField(orderItemDesc.findFieldByName("quantity")).asInstanceOf[Int])
      val priceField = orderItemDesc.findFieldByName("unit_price")
      if (item.hasField(priceField)) {
        val price = item.getField(priceField).asInstanceOf[DynamicMessage]
        val c = price.getField(moneyDesc.findFieldByName("currency")).asInstanceOf[String]
        if (c.nonEmpty) currency = c
        val units = price.getField(moneyDesc.findFieldByName("units")).asInstanceOf[Long]
        val nanos = price.getField(moneyDesc.findFieldByName("nanos")).asInstanceOf[Int]
        totalNanosSum += qty.toLong * (units * 1000000000L + nanos.toLong)
      }
    }

    val orderId = s"ord-${sequence.incrementAndGet()}"
    val priority = {
      val p = req.getField(reqDesc.findFieldByName("priority")).asInstanceOf[Descriptors.EnumValueDescriptor]
      if (p.getNumber == 0) priorityEnum.findValueByName("PRIORITY_MEDIUM") else p
    }

    val builder = DynamicMessage.newBuilder(orderDesc)
      .setField(orderDesc.findFieldByName("order_id"), orderId)
      .setField(orderDesc.findFieldByName("customer_id"), customerId)
      .setField(orderDesc.findFieldByName("status"), orderStatusEnum.findValueByName("ORDER_STATUS_PENDING"))
      .setField(orderDesc.findFieldByName("total"), buildMoney(currency, totalNanosSum / 1000000000L, (totalNanosSum % 1000000000L).toInt))
      .setField(orderDesc.findFieldByName("priority"), priority)
      .setField(orderDesc.findFieldByName("created_at"), nowTimestamp())

    val itemsField = orderDesc.findFieldByName("items")
    items.foreach(i => builder.addRepeatedField(itemsField, i))

    val labelsField = orderDesc.findFieldByName("labels")
    val labelsEntry = labelsField.getMessageType
    toMap(req, "labels").toSeq.sortBy(_._1).foreach { case (k, v) =>
      builder.addRepeatedField(labelsField, buildMapEntry(labelsEntry, k, v))
    }

    val created = builder.build()
    orders.put(orderId, created)
    Right(created)
  }

  private def handleGetOrder(req: DynamicMessage): Either[Status, DynamicMessage] = {
    val orderId = req.getField(req.getDescriptorForType.findFieldByName("order_id")).asInstanceOf[String].trim
    if (orderId.isEmpty) {
      Left(Status.INVALID_ARGUMENT.withDescription("order_id must not be empty (try 'ord-1001' or 'ord-1002')"))
    } else {
      Option(orders.get(orderId)) match {
        case Some(order) => Right(order)
        case None        => Left(Status.NOT_FOUND.withDescription(s"Order '$orderId' not found (seeded: ord-1001, ord-1002)"))
      }
    }
  }

  private def handleListOrders(req: DynamicMessage): Either[Status, DynamicMessage] = {
    val reqDesc = req.getDescriptorForType
    val customerFilter = req.getField(reqDesc.findFieldByName("customer_id")).asInstanceOf[String].trim
    val statusFilter = req.getField(reqDesc.findFieldByName("status_filter")).asInstanceOf[Descriptors.EnumValueDescriptor]

    val matching = orders.values().asScala.toList
      .filter { o =>
        val custMatch = customerFilter.isEmpty ||
          o.getField(orderDesc.findFieldByName("customer_id")).asInstanceOf[String] == customerFilter
        val statusVal = o.getField(orderDesc.findFieldByName("status")).asInstanceOf[Descriptors.EnumValueDescriptor]
        val statusMatch = statusFilter.getNumber == 0 || statusVal.getNumber == statusFilter.getNumber
        custMatch && statusMatch
      }
      .sortBy(_.getField(orderDesc.findFieldByName("order_id")).asInstanceOf[String])

    val builder = DynamicMessage.newBuilder(listOrdersRespDesc)
      .setField(listOrdersRespDesc.findFieldByName("total_count"), Int.box(matching.size))
    val listField = listOrdersRespDesc.findFieldByName("orders")
    matching.foreach(o => builder.addRepeatedField(listField, o))
    Right(builder.build())
  }

  private def handleGetAdminAudit(req: DynamicMessage): Either[Status, DynamicMessage] = {
    val headers = RequestHeadersContextKey.get()
    val bearerAuth = headers.find(_.key.equalsIgnoreCase("authorization"))
      .map(_.value.trim)
      .filter(_.equalsIgnoreCase(s"Bearer $DemoBearerToken"))
      .map(_ => "bearer-admin")
    val apiKeyAuth = headers.find(_.key.equalsIgnoreCase("x-api-key"))
      .map(_.value.trim)
      .filter(_ == DemoApiKey)
      .map(_ => "api-key-admin")

    bearerAuth.orElse(apiKeyAuth) match {
      case None =>
        Left(Status.UNAUTHENTICATED.withDescription(
          s"Missing or invalid credentials. Send 'authorization: Bearer $DemoBearerToken' or 'x-api-key: $DemoApiKey'"
        ))
      case Some(principal) =>
        val includeIds = req.getField(req.getDescriptorForType.findFieldByName("include_order_ids")).asInstanceOf[Boolean]
        val allIds = orders.keySet().asScala.toList.sorted
        val builder = DynamicMessage.newBuilder(adminAuditRespDesc)
          .setField(adminAuditRespDesc.findFieldByName("authenticated_as"), principal)
          .setField(adminAuditRespDesc.findFieldByName("total_orders"), Int.box(allIds.size))
          .setField(adminAuditRespDesc.findFieldByName("audited_at"), nowTimestamp())

        if (includeIds) {
          val idsField = adminAuditRespDesc.findFieldByName("order_ids")
          allIds.foreach(id => builder.addRepeatedField(idsField, id))
        }
        Right(builder.build())
    }
  }

  private def handleWatchOrderUpdates(req: DynamicMessage, observer: StreamObserver[DynamicMessage]): Unit = {
    val reqDesc = req.getDescriptorForType
    val rawOrderId = req.getField(reqDesc.findFieldByName("order_id")).asInstanceOf[String].trim
    val orderId = if (rawOrderId.isEmpty) "ord-1001" else rawOrderId
    val rawDelay = req.getField(reqDesc.findFieldByName("step_delay_ms")).asInstanceOf[Int]
    val delayMs = if (rawDelay <= 0) 350 else Math.min(rawDelay, 5000)

    if (!orders.containsKey(orderId)) {
      observer.onError(Status.NOT_FOUND.withDescription(s"Order '$orderId' not found").asRuntimeException())
      return
    }

    val stages = Seq(
      "ORDER_STATUS_PENDING" -> "Order received by fulfillment",
      "ORDER_STATUS_CONFIRMED" -> "Payment verified and inventory reserved",
      "ORDER_STATUS_PACKED" -> "Items packed at warehouse",
      "ORDER_STATUS_SHIPPED" -> "Handed off to carrier",
      "ORDER_STATUS_DELIVERED" -> "Delivered to customer"
    )
    val ctx = Context.current()

    def emitStage(idx: Int): Unit = {
      if (ctx.isCancelled) return
      if (idx >= stages.size) {
        observer.onCompleted()
        return
      }
      val (statusName, msg) = stages(idx)
      val update = DynamicMessage.newBuilder(orderUpdateDesc)
        .setField(orderUpdateDesc.findFieldByName("order_id"), orderId)
        .setField(orderUpdateDesc.findFieldByName("status"), orderStatusEnum.findValueByName(statusName))
        .setField(orderUpdateDesc.findFieldByName("message"), msg)
        .setField(orderUpdateDesc.findFieldByName("updated_at"), nowTimestamp())
        .build()

      try {
        observer.onNext(update)
      } catch {
        case _: Throwable => return
      }

      if (idx + 1 >= stages.size) {
        observer.onCompleted()
      } else {
        scheduler.schedule(
          new Runnable {
            override def run(): Unit = emitStage(idx + 1)
          },
          delayMs.toLong,
          TimeUnit.MILLISECONDS
        )
      }
    }

    scheduler.execute(new Runnable {
      override def run(): Unit = emitStage(0)
    })
  }
}
