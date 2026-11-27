package net.protoform.core.call

final case class RpcCallOptions(
  deadlineSeconds: Int = 0,
  useGzipCompression: Boolean = false,
  includeDefaultFields: Boolean = true,
  preserveProtoFieldNames: Boolean = true
)
