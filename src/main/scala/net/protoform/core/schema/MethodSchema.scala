package net.protoform.core.schema

enum RpcPattern {
  case Unary
  case ServerStreaming
  case ClientStreaming
  case BidirectionalStreaming

  def isStreaming: Boolean = this != Unary
  def label: String = this match {
    case Unary                  => "Unary"
    case ServerStreaming        => "Server Streaming"
    case ClientStreaming        => "Client Streaming"
    case BidirectionalStreaming => "Bidirectional Streaming"
  }
}

final case class MethodSchema(
  name: String,
  fullMethodName: String,
  serviceFullName: String,
  pattern: RpcPattern,
  inputSchema: ProtoType.MessageType,
  outputSchema: ProtoType.MessageType,
  schemaHandle: SchemaHandle = SchemaHandle.Empty
) {
  def descriptorMethodName: String = fullMethodName
}
