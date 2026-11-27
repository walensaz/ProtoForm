package net.protoform.demo

import com.google.protobuf.{DescriptorProtos, Descriptors, DurationProto, FieldMaskProto, StructProto, TimestampProto}
import com.google.protobuf.DescriptorProtos.{DescriptorProto, EnumDescriptorProto, EnumValueDescriptorProto, FieldDescriptorProto, FileDescriptorProto, FileDescriptorSet, MessageOptions, MethodDescriptorProto, OneofDescriptorProto, ServiceDescriptorProto}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

object DemoProtoSchema {

  private val wellKnownDeps: Array[Descriptors.FileDescriptor] = Array(
    TimestampProto.getDescriptor,
    DurationProto.getDescriptor,
    FieldMaskProto.getDescriptor,
    StructProto.getDescriptor
  )

  private def scalarField(
    name: String,
    number: Int,
    fieldType: FieldDescriptorProto.Type,
    repeated: Boolean = false
  ): FieldDescriptorProto = {
    val label = if (repeated) FieldDescriptorProto.Label.LABEL_REPEATED else FieldDescriptorProto.Label.LABEL_OPTIONAL
    FieldDescriptorProto.newBuilder()
      .setName(name)
      .setNumber(number)
      .setLabel(label)
      .setType(fieldType)
      .build()
  }

  private def typeField(
    name: String,
    number: Int,
    fieldType: FieldDescriptorProto.Type,
    typeName: String,
    repeated: Boolean = false,
    oneofIndex: Option[Int] = None
  ): FieldDescriptorProto = {
    val label = if (repeated) FieldDescriptorProto.Label.LABEL_REPEATED else FieldDescriptorProto.Label.LABEL_OPTIONAL
    val b = FieldDescriptorProto.newBuilder()
      .setName(name)
      .setNumber(number)
      .setLabel(label)
      .setType(fieldType)
      .setTypeName(typeName)
    oneofIndex.foreach(b.setOneofIndex)
    b.build()
  }

  private def oneofScalar(
    name: String,
    number: Int,
    fieldType: FieldDescriptorProto.Type,
    oneofIndex: Int
  ): FieldDescriptorProto = {
    FieldDescriptorProto.newBuilder()
      .setName(name)
      .setNumber(number)
      .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
      .setType(fieldType)
      .setOneofIndex(oneofIndex)
      .build()
  }

  private def mapStringEntry(entryName: String): DescriptorProto = {
    DescriptorProto.newBuilder()
      .setName(entryName)
      .setOptions(MessageOptions.newBuilder().setMapEntry(true).build())
      .addField(scalarField("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("value", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .build()
  }

  private def enumDef(name: String, values: Seq[(String, Int)]): EnumDescriptorProto = {
    val b = EnumDescriptorProto.newBuilder().setName(name)
    values.foreach { case (vName, num) =>
      b.addValue(EnumValueDescriptorProto.newBuilder().setName(vName).setNumber(num).build())
    }
    b.build()
  }

  private def rpcMethod(
    name: String,
    inputType: String,
    outputType: String,
    clientStreaming: Boolean = false,
    serverStreaming: Boolean = false
  ): MethodDescriptorProto = {
    MethodDescriptorProto.newBuilder()
      .setName(name)
      .setInputType(inputType)
      .setOutputType(outputType)
      .setClientStreaming(clientStreaming)
      .setServerStreaming(serverStreaming)
      .build()
  }

  val commonFd: Descriptors.FileDescriptor = {
    val priorityEnum = enumDef(
      "Priority",
      Seq(
        "PRIORITY_UNSPECIFIED" -> 0,
        "PRIORITY_LOW" -> 1,
        "PRIORITY_MEDIUM" -> 2,
        "PRIORITY_HIGH" -> 3,
        "PRIORITY_CRITICAL" -> 4
      )
    )

    val moneyMsg = DescriptorProto.newBuilder()
      .setName("Money")
      .addField(scalarField("currency", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("units", 2, FieldDescriptorProto.Type.TYPE_INT64))
      .addField(scalarField("nanos", 3, FieldDescriptorProto.Type.TYPE_INT32))
      .build()

    val clientInfoMsg = DescriptorProto.newBuilder()
      .setName("ClientInfo")
      .addField(scalarField("client_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("version", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("dry_run", 3, FieldDescriptorProto.Type.TYPE_BOOL))
      .build()

    val headerEntryMsg = DescriptorProto.newBuilder()
      .setName("HeaderEntry")
      .addField(scalarField("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("value", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .build()

    val fileProto = FileDescriptorProto.newBuilder()
      .setName("demo/common.proto")
      .setPackage("demo.common.v1")
      .setSyntax("proto3")
      .addEnumType(priorityEnum)
      .addMessageType(moneyMsg)
      .addMessageType(clientInfoMsg)
      .addMessageType(headerEntryMsg)
      .build()

    Descriptors.FileDescriptor.buildFrom(fileProto, Array.empty[Descriptors.FileDescriptor])
  }

  val sandboxFd: Descriptors.FileDescriptor = {
    val echoRequestMsg = DescriptorProto.newBuilder()
      .setName("EchoRequest")
      .addField(scalarField("message", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("count", 2, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("score", 3, FieldDescriptorProto.Type.TYPE_DOUBLE))
      .addField(scalarField("active", 4, FieldDescriptorProto.Type.TYPE_BOOL))
      .addField(scalarField("raw_payload", 5, FieldDescriptorProto.Type.TYPE_BYTES))
      .addField(typeField("priority", 6, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.common.v1.Priority"))
      .addField(typeField("client", 7, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.common.v1.ClientInfo"))
      .addField(scalarField("tags", 8, FieldDescriptorProto.Type.TYPE_STRING, repeated = true))
      .addField(typeField("attributes", 9, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.sandbox.v1.EchoRequest.AttributesEntry", repeated = true))
      .addField(typeField("requested_at", 10, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .addField(typeField("timeout_hint", 11, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Duration"))
      .addField(typeField("update_mask", 12, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.FieldMask"))
      .addField(oneofScalar("note", 13, FieldDescriptorProto.Type.TYPE_STRING, oneofIndex = 0))
      .addField(oneofScalar("code", 14, FieldDescriptorProto.Type.TYPE_INT64, oneofIndex = 0))
      .addField(typeField("extra", 15, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Struct", oneofIndex = Some(0)))
      .addOneofDecl(OneofDescriptorProto.newBuilder().setName("detail").build())
      .addNestedType(mapStringEntry("AttributesEntry"))
      .build()

    val echoResponseMsg = DescriptorProto.newBuilder()
      .setName("EchoResponse")
      .addField(scalarField("message", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("count", 2, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("score", 3, FieldDescriptorProto.Type.TYPE_DOUBLE))
      .addField(scalarField("active", 4, FieldDescriptorProto.Type.TYPE_BOOL))
      .addField(scalarField("raw_payload_bytes", 5, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(typeField("priority", 6, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.common.v1.Priority"))
      .addField(typeField("client", 7, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.common.v1.ClientInfo"))
      .addField(scalarField("tags", 8, FieldDescriptorProto.Type.TYPE_STRING, repeated = true))
      .addField(typeField("attributes", 9, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.sandbox.v1.EchoResponse.AttributesEntry", repeated = true))
      .addField(typeField("received_at", 10, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .addField(typeField("server_elapsed", 11, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Duration"))
      .addField(typeField("received_headers", 12, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.common.v1.HeaderEntry", repeated = true))
      .addField(scalarField("detail_summary", 13, FieldDescriptorProto.Type.TYPE_STRING))
      .addNestedType(mapStringEntry("AttributesEntry"))
      .build()

    val statusEnum = enumDef(
      "GrpcStatusCode",
      Seq(
        "GRPC_STATUS_CODE_UNSPECIFIED" -> 0,
        "GRPC_STATUS_CODE_OK" -> 1,
        "GRPC_STATUS_CODE_INVALID_ARGUMENT" -> 2,
        "GRPC_STATUS_CODE_NOT_FOUND" -> 3,
        "GRPC_STATUS_CODE_PERMISSION_DENIED" -> 4,
        "GRPC_STATUS_CODE_UNAUTHENTICATED" -> 5,
        "GRPC_STATUS_CODE_RESOURCE_EXHAUSTED" -> 6,
        "GRPC_STATUS_CODE_UNAVAILABLE" -> 7,
        "GRPC_STATUS_CODE_INTERNAL" -> 8
      )
    )

    val behaviorReqMsg = DescriptorProto.newBuilder()
      .setName("BehaviorRequest")
      .addField(typeField("status_code", 1, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.sandbox.v1.GrpcStatusCode"))
      .addField(scalarField("delay_ms", 2, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("error_message", 3, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("detail_hint", 4, FieldDescriptorProto.Type.TYPE_STRING))
      .build()

    val behaviorRespMsg = DescriptorProto.newBuilder()
      .setName("BehaviorResponse")
      .addField(scalarField("ok", 1, FieldDescriptorProto.Type.TYPE_BOOL))
      .addField(scalarField("status_name", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("delayed_ms", 3, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("note", 4, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("completed_at", 5, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .build()

    val streamEventsReqMsg = DescriptorProto.newBuilder()
      .setName("StreamEventsRequest")
      .addField(scalarField("topic", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("count", 2, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("interval_ms", 3, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(typeField("min_priority", 4, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.common.v1.Priority"))
      .build()

    val serverEventMsg = DescriptorProto.newBuilder()
      .setName("ServerEvent")
      .addField(scalarField("sequence", 1, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("topic", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("priority", 3, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.common.v1.Priority"))
      .addField(scalarField("payload", 4, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("emitted_at", 5, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .addField(typeField("elapsed", 6, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Duration"))
      .addField(typeField("metadata", 7, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.sandbox.v1.ServerEvent.MetadataEntry", repeated = true))
      .addNestedType(mapStringEntry("MetadataEntry"))
      .build()

    val metricPointMsg = DescriptorProto.newBuilder()
      .setName("MetricPoint")
      .addField(scalarField("metric_name", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("value", 2, FieldDescriptorProto.Type.TYPE_DOUBLE))
      .addField(typeField("labels", 3, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.sandbox.v1.MetricPoint.LabelsEntry", repeated = true))
      .addField(typeField("recorded_at", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .addNestedType(mapStringEntry("LabelsEntry"))
      .build()

    val metricSummaryMsg = DescriptorProto.newBuilder()
      .setName("MetricSummary")
      .addField(scalarField("count", 1, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("sum", 2, FieldDescriptorProto.Type.TYPE_DOUBLE))
      .addField(scalarField("min", 3, FieldDescriptorProto.Type.TYPE_DOUBLE))
      .addField(scalarField("max", 4, FieldDescriptorProto.Type.TYPE_DOUBLE))
      .addField(scalarField("average", 5, FieldDescriptorProto.Type.TYPE_DOUBLE))
      .addField(scalarField("distinct_metrics", 6, FieldDescriptorProto.Type.TYPE_STRING, repeated = true))
      .build()

    val chatMessageMsg = DescriptorProto.newBuilder()
      .setName("ChatMessage")
      .addField(scalarField("sender", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("body", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("sent_at", 3, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .addField(scalarField("mood", 4, FieldDescriptorProto.Type.TYPE_STRING))
      .build()

    val sandboxService = ServiceDescriptorProto.newBuilder()
      .setName("SandboxService")
      .addMethod(rpcMethod("Echo", ".demo.sandbox.v1.EchoRequest", ".demo.sandbox.v1.EchoResponse"))
      .addMethod(rpcMethod("SimulateBehavior", ".demo.sandbox.v1.BehaviorRequest", ".demo.sandbox.v1.BehaviorResponse"))
      .addMethod(rpcMethod("StreamEvents", ".demo.sandbox.v1.StreamEventsRequest", ".demo.sandbox.v1.ServerEvent", serverStreaming = true))
      .addMethod(rpcMethod("CollectMetrics", ".demo.sandbox.v1.MetricPoint", ".demo.sandbox.v1.MetricSummary", clientStreaming = true))
      .addMethod(rpcMethod("InteractiveChat", ".demo.sandbox.v1.ChatMessage", ".demo.sandbox.v1.ChatMessage", clientStreaming = true, serverStreaming = true))
      .build()

    val fileProto = FileDescriptorProto.newBuilder()
      .setName("demo/sandbox.proto")
      .setPackage("demo.sandbox.v1")
      .setSyntax("proto3")
      .addDependency("demo/common.proto")
      .addDependency("google/protobuf/timestamp.proto")
      .addDependency("google/protobuf/duration.proto")
      .addDependency("google/protobuf/field_mask.proto")
      .addDependency("google/protobuf/struct.proto")
      .addEnumType(statusEnum)
      .addMessageType(echoRequestMsg)
      .addMessageType(echoResponseMsg)
      .addMessageType(behaviorReqMsg)
      .addMessageType(behaviorRespMsg)
      .addMessageType(streamEventsReqMsg)
      .addMessageType(serverEventMsg)
      .addMessageType(metricPointMsg)
      .addMessageType(metricSummaryMsg)
      .addMessageType(chatMessageMsg)
      .addService(sandboxService)
      .build()

    Descriptors.FileDescriptor.buildFrom(fileProto, Array(commonFd) ++ wellKnownDeps)
  }

  val ordersFd: Descriptors.FileDescriptor = {
    val orderStatusEnum = enumDef(
      "OrderStatus",
      Seq(
        "ORDER_STATUS_UNSPECIFIED" -> 0,
        "ORDER_STATUS_PENDING" -> 1,
        "ORDER_STATUS_CONFIRMED" -> 2,
        "ORDER_STATUS_PACKED" -> 3,
        "ORDER_STATUS_SHIPPED" -> 4,
        "ORDER_STATUS_DELIVERED" -> 5
      )
    )

    val orderItemMsg = DescriptorProto.newBuilder()
      .setName("OrderItem")
      .addField(scalarField("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("quantity", 3, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(typeField("unit_price", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.common.v1.Money"))
      .build()

    val orderMsg = DescriptorProto.newBuilder()
      .setName("Order")
      .addField(scalarField("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("customer_id", 2, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("status", 3, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.orders.v1.OrderStatus"))
      .addField(typeField("items", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.orders.v1.OrderItem", repeated = true))
      .addField(typeField("total", 5, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.common.v1.Money"))
      .addField(typeField("priority", 6, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.common.v1.Priority"))
      .addField(typeField("labels", 7, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.orders.v1.Order.LabelsEntry", repeated = true))
      .addField(typeField("created_at", 8, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .addNestedType(mapStringEntry("LabelsEntry"))
      .build()

    val createOrderReqMsg = DescriptorProto.newBuilder()
      .setName("CreateOrderRequest")
      .addField(scalarField("customer_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("items", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.orders.v1.OrderItem", repeated = true))
      .addField(typeField("priority", 3, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.common.v1.Priority"))
      .addField(typeField("labels", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.orders.v1.CreateOrderRequest.LabelsEntry", repeated = true))
      .addNestedType(mapStringEntry("LabelsEntry"))
      .build()

    val getOrderReqMsg = DescriptorProto.newBuilder()
      .setName("GetOrderRequest")
      .addField(scalarField("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .build()

    val listOrdersReqMsg = DescriptorProto.newBuilder()
      .setName("ListOrdersRequest")
      .addField(scalarField("customer_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("status_filter", 2, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.orders.v1.OrderStatus"))
      .build()

    val listOrdersRespMsg = DescriptorProto.newBuilder()
      .setName("ListOrdersResponse")
      .addField(typeField("orders", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".demo.orders.v1.Order", repeated = true))
      .addField(scalarField("total_count", 2, FieldDescriptorProto.Type.TYPE_INT32))
      .build()

    val adminAuditReqMsg = DescriptorProto.newBuilder()
      .setName("AdminAuditRequest")
      .addField(scalarField("include_order_ids", 1, FieldDescriptorProto.Type.TYPE_BOOL))
      .build()

    val adminAuditRespMsg = DescriptorProto.newBuilder()
      .setName("AdminAuditResponse")
      .addField(scalarField("authenticated_as", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("total_orders", 2, FieldDescriptorProto.Type.TYPE_INT32))
      .addField(scalarField("order_ids", 3, FieldDescriptorProto.Type.TYPE_STRING, repeated = true))
      .addField(typeField("audited_at", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .build()

    val watchOrderReqMsg = DescriptorProto.newBuilder()
      .setName("WatchOrderRequest")
      .addField(scalarField("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(scalarField("step_delay_ms", 2, FieldDescriptorProto.Type.TYPE_INT32))
      .build()

    val orderUpdateMsg = DescriptorProto.newBuilder()
      .setName("OrderUpdate")
      .addField(scalarField("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("status", 2, FieldDescriptorProto.Type.TYPE_ENUM, ".demo.orders.v1.OrderStatus"))
      .addField(scalarField("message", 3, FieldDescriptorProto.Type.TYPE_STRING))
      .addField(typeField("updated_at", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Timestamp"))
      .build()

    val orderService = ServiceDescriptorProto.newBuilder()
      .setName("OrderService")
      .addMethod(rpcMethod("CreateOrder", ".demo.orders.v1.CreateOrderRequest", ".demo.orders.v1.Order"))
      .addMethod(rpcMethod("GetOrder", ".demo.orders.v1.GetOrderRequest", ".demo.orders.v1.Order"))
      .addMethod(rpcMethod("ListOrders", ".demo.orders.v1.ListOrdersRequest", ".demo.orders.v1.ListOrdersResponse"))
      .addMethod(rpcMethod("GetAdminAudit", ".demo.orders.v1.AdminAuditRequest", ".demo.orders.v1.AdminAuditResponse"))
      .addMethod(rpcMethod("WatchOrderUpdates", ".demo.orders.v1.WatchOrderRequest", ".demo.orders.v1.OrderUpdate", serverStreaming = true))
      .build()

    val fileProto = FileDescriptorProto.newBuilder()
      .setName("demo/orders.proto")
      .setPackage("demo.orders.v1")
      .setSyntax("proto3")
      .addDependency("demo/common.proto")
      .addDependency("google/protobuf/timestamp.proto")
      .addEnumType(orderStatusEnum)
      .addMessageType(orderItemMsg)
      .addMessageType(orderMsg)
      .addMessageType(createOrderReqMsg)
      .addMessageType(getOrderReqMsg)
      .addMessageType(listOrdersReqMsg)
      .addMessageType(listOrdersRespMsg)
      .addMessageType(adminAuditReqMsg)
      .addMessageType(adminAuditRespMsg)
      .addMessageType(watchOrderReqMsg)
      .addMessageType(orderUpdateMsg)
      .addService(orderService)
      .build()

    Descriptors.FileDescriptor.buildFrom(fileProto, Array(commonFd, TimestampProto.getDescriptor))
  }

  def buildFileDescriptorSet(): FileDescriptorSet = {
    val allFiles = Seq(
      TimestampProto.getDescriptor,
      DurationProto.getDescriptor,
      FieldMaskProto.getDescriptor,
      StructProto.getDescriptor,
      commonFd,
      sandboxFd,
      ordersFd
    )
    val builder = FileDescriptorSet.newBuilder()
    allFiles.foreach(fd => builder.addFile(fd.toProto))
    builder.build()
  }

  def writeFileDescriptorSet(outputPath: Path): Unit = {
    Option(outputPath.getParent).foreach(p => Files.createDirectories(p))
    Files.write(outputPath, buildFileDescriptorSet().toByteArray)
  }
}
