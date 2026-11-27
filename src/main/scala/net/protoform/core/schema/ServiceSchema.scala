package net.protoform.core.schema

final case class ServiceSchema(
  fullName: String,
  packageName: String,
  simpleName: String,
  methods: List[MethodSchema],
  schemaHandle: SchemaHandle = SchemaHandle.Empty,
  protoFile: Option[String] = None
)
