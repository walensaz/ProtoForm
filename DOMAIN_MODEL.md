# ProtoForm — Domain-Driven Design (DDD) Specification

> **Status**: Living Architectural Specification  
> **Target Audience**: Backend Engineers, UI Engineers, System Architects, and AI Agents.

---

## 1. Domain Overview & Philosophy

**ProtoForm** eliminates the friction of introspecting and testing gRPC microservices. Unlike REST, where schemas are often ad-hoc, gRPC relies on strict binary Protocol Buffer contracts.

### Core Domain Principles
1. **Schema-First Introspection**: A remote gRPC server is a living schema repository. Through Server Reflection (`v1` with `v1alpha` fallback), ProtoForm dynamically discovers service topology without manual `.proto` compilation.
2. **Explicit Default-Value Visibility**: Protobuf v3 wire serialization elides zero-value fields (`""`, `0`, `false`, default enums). ProtoForm mandates **default-value transparency** so developers always see every declared field and its active value.
3. **Domain Independence**: Domain models, schema AST (`ServiceSchema`, `MethodSchema`, `ProtoType`, `FieldSchema`, `SchemaHandle`), auth presets, call states, diffs, and errors are 100% pure Scala, isolated from JavaFX UI controls, Protobuf runtime descriptors (held in infrastructure `DescriptorRegistry`), and gRPC Netty transport.
4. **Bi-Directional Contract Parity**: Visual forms and JSON payloads are interchangeable views of the same pure `ProtoType.MessageType` contract, marshaled dynamically at execution time via `DescriptorRegistry`.

---

## 2. Ubiquitous Language & Domain Glossary

| Term | Domain Definition | Code Representation |
|---|---|---|
| **Endpoint Target** | Network destination for a gRPC server (host, port, TLS, mTLS client cert/key, authority, custom CA cert), supporting IPv4, hostnames, and bracketed IPv6. | `EndpointTarget` (`core.model`) |
| **Identifiers** | Strongly-typed opaque string IDs for targets and RPC calls. | `TargetId`, `CallId` (`core.model`) |
| **Service Schema** | Pure Scala model of a discovered gRPC service with qualified name, package, simple name, RPC methods, and opaque `SchemaHandle`. | `ServiceSchema`, `SchemaHandle` (`core.schema`) |
| **Method Schema** | Pure Scala model of an RPC procedure with name, full method name, streaming pattern, I/O `ProtoType.MessageType` schemas, and `SchemaHandle`. | `MethodSchema`, `RpcPattern` (`core.schema`) |
| **Environment Config** | Named environment bundle (`local`, `staging`, `qa`, `prod`) with endpoint, TLS/mTLS overrides, linked `AuthProfile`, and `{{var}}` dictionary. | `EnvironmentConfig`, `VariableInterpolator` (`core.workspace`) |
| **RPC Pattern** | Streaming mode: Unary, Server Streaming, Client Streaming, or Bidirectional Streaming. | `RpcPattern` enum (`core.schema`) |
| **Field Schema** | Single field within a Protobuf message (name, jsonName, number, type, repeated/required/optional flags, default value, oneof group). | `FieldSchema` (`core.schema`) |
| **Proto Type** | Sealed ADT representing Protobuf scalars, enums, lazy-field messages, and maps. | `ProtoType` (`core.schema`) |
| **Auth Preset** | Configured authentication mechanism (`None`, `ApiKey`, `BearerToken`, `BasicAuth`, `GoogleCloud`, `SignedJwt`, `Custom`) translated to metadata headers (static or dynamically resolved). | `AuthPreset` (`core.auth`) |
| **Auth Profile** | Named, reusable authentication preset plus custom metadata headers (project-scoped or global across all projects). | `AuthProfile` (`core.auth`) |
| **Metadata Header** | Key-value header attached to a gRPC call (supports `-bin` binary headers). | `MetadataHeader` (`core.auth`) |
| **RPC Call** | Execution snapshot tracking target, method, status, request/response JSON, headers, trailers, stream chunks, and latency. | `RpcCall`, `RpcCallStatus`, `StreamChunk` (`core.call`) |
| **Workspace Project & Data** | Persisted user state: global `AppSettings`, global auth profiles, and named `WorkspaceProject`s each owning endpoints, method payloads, foldered QA templates, `lastTemplateByMethod`, auth profiles, environments, workbench tabs, and 30-entry history. | `WorkspaceData`, `WorkspaceProject`, `SavedWorkbenchTab`, `CallHistoryEntry` (`core.workspace`) |

---

## 3. Aggregates, Entities & Value Objects

### 3.1 EndpointTarget & Identifiers (`net.protoform.core.model`)

```scala
opaque type TargetId = String
object TargetId {
  def apply(value: String): TargetId = value
  def random(): TargetId = UUID.randomUUID().toString
  extension (id: TargetId) {
    def value: String = id
  }
}

opaque type CallId = String
object CallId {
  def apply(value: String): CallId = value
  def random(): CallId = UUID.randomUUID().toString
  extension (id: CallId) {
    def value: String = id
  }
}

final case class EndpointTarget(
  id: TargetId = TargetId.random(),
  name: String,
  host: String,
  port: Int,
  useTls: Boolean = false,
  overrideAuthority: Option[String] = None,
  tlsCertPath: Option[String] = None,
  insecureSkipTlsVerify: Boolean = false,
  clientCertPath: Option[String] = None,
  clientKeyPath: Option[String] = None
) {
  private def formattedHost: String = if (host.contains(":")) s"[$host]" else host
  def uriString: String = s"${if (useTls) "https" else "http"}://$formattedHost:$port"
  def authority: String = overrideAuthority.getOrElse(s"$formattedHost:$port")
  def displayLabel: String = s"$formattedHost:$port ${if (useTls) "(TLS)" else "(Plaintext)"}"
}
```

**Invariants (enforced in `EndpointTarget.parse`):**
- `host` cannot be empty (rejects `""`, `":50051"`, `"[]:50051"`).
- `port` must be in `[1, 65535]`; omitted port defaults to `443` (TLS) or `80` (plaintext).
- Trailing slashes and `http://`/`https://` prefixes are stripped; bracketed IPv6 literals (e.g. `[::1]:50051`) store raw `host = "::1"` for Netty and re-bracket in `uriString`/`authority`/`displayLabel`.
- If `tlsCertPath` is present, it must point to a readable certificate file.

---

### 3.2 Protobuf Schema & Field AST (`net.protoform.core.schema`)

```scala
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

sealed trait ProtoType {
  def typeName: String
  def defaultScalarValue: Any
}

object ProtoType {
  case object DoubleType extends ProtoType { val typeName = "double"; val defaultScalarValue: Any = 0.0 }
  case object FloatType extends ProtoType { val typeName = "float"; val defaultScalarValue: Any = 0.0f }
  case object Int64Type extends ProtoType { val typeName = "int64"; val defaultScalarValue: Any = 0L }
  case object UInt64Type extends ProtoType { val typeName = "uint64"; val defaultScalarValue: Any = 0L }
  case object Int32Type extends ProtoType { val typeName = "int32"; val defaultScalarValue: Any = 0 }
  case object Fixed64Type extends ProtoType { val typeName = "fixed64"; val defaultScalarValue: Any = 0L }
  case object Fixed32Type extends ProtoType { val typeName = "fixed32"; val defaultScalarValue: Any = 0 }
  case object BoolType extends ProtoType { val typeName = "bool"; val defaultScalarValue: Any = false }
  case object StringType extends ProtoType { val typeName = "string"; val defaultScalarValue: Any = "" }
  case object BytesType extends ProtoType { val typeName = "bytes"; val defaultScalarValue: Any = "" }
  case object UInt32Type extends ProtoType { val typeName = "uint32"; val defaultScalarValue: Any = 0 }
  case object SFixed32Type extends ProtoType { val typeName = "sfixed32"; val defaultScalarValue: Any = 0 }
  case object SFixed64Type extends ProtoType { val typeName = "sfixed64"; val defaultScalarValue: Any = 0L }
  case object SInt32Type extends ProtoType { val typeName = "sint32"; val defaultScalarValue: Any = 0 }
  case object SInt64Type extends ProtoType { val typeName = "sint64"; val defaultScalarValue: Any = 0L }

  final case class EnumValue(name: String, number: Int)

  final case class EnumType(fullName: String, values: List[EnumValue]) extends ProtoType {
    val typeName = s"enum $fullName"
    val defaultScalarValue: Any = values.headOption.map(_.name).getOrElse("")
  }

  // Lazy thunk prevents StackOverflowError on self-referential / mutually recursive messages.
  final case class MessageType(fullName: String, fields: () => List[FieldSchema]) extends ProtoType {
    val typeName = s"message $fullName"
    val defaultScalarValue: Any = Map.empty[String, Any]
  }

  final case class MapType(keyType: ProtoType, valueType: ProtoType) extends ProtoType {
    val typeName = s"map<${keyType.typeName}, ${valueType.typeName}>"
    val defaultScalarValue: Any = Map.empty[Any, Any]
  }
}

final case class FieldSchema(
  name: String,
  jsonName: String,
  number: Int,
  protoType: ProtoType,
  isRepeated: Boolean,
  isRequired: Boolean,
  defaultValue: Any,
  oneofGroup: Option[String] = None,
  documentation: Option[String] = None,
  isOptional: Boolean = false
) {
  def isMap: Boolean = protoType.isInstanceOf[ProtoType.MapType]
  def isMessage: Boolean = protoType.isInstanceOf[ProtoType.MessageType]
  def isEnum: Boolean = protoType.isInstanceOf[ProtoType.EnumType]
}

final case class SchemaHandle(value: String) extends AnyVal

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

final case class ServiceSchema(
  fullName: String,
  packageName: String,
  simpleName: String,
  methods: List[MethodSchema],
  schemaHandle: SchemaHandle = SchemaHandle.Empty,
  protoFile: Option[String] = None
)
```

**Default Value Contract:**
- `DoubleType` / `FloatType` → `0.0` / `0.0f`
- `Int32`/`UInt32`/`SInt32`/`Fixed32`/`SFixed32` → `0`; `Int64`/`UInt64`/`SInt64`/`Fixed64`/`SFixed64` → `0L`
- `BoolType` → `false`; `StringType` / `BytesType` → `""`
- `EnumType` → first value in `values` (typically tag `0`)
- `Repeated` → `List.empty`; `MapType` → `Map.empty`; `MessageType` → nested defaults (cycle-guarded in `FormSchemaService` with visited set and max depth 8; `oneof` groups populate at most one field).

---

### 3.3 Authentication & Metadata (`net.protoform.core.auth`)

```scala
final case class MetadataHeader(
  key: String,
  value: String,
  enabled: Boolean = true
) {
  def isBinary: Boolean = key.endsWith("-bin")
}

sealed trait AuthPreset {
  def toHeaders: List[MetadataHeader]
  def presetType: String
  def toDataMap: Map[String, String]
  def requiresDynamicResolution: Boolean = false
}

object AuthPreset {
  case object None extends AuthPreset
  final case class ApiKey(headerName: String = "x-api-key", key: String) extends AuthPreset
  final case class BearerToken(token: String) extends AuthPreset
  final case class BasicAuth(username: String, password: String) extends AuthPreset
  final case class GoogleCloud(tokenMode: String = "AccessToken", serviceAccountJsonPath: Option[String] = scala.None, audience: Option[String] = scala.None, scopes: List[String] = List("https://www.googleapis.com/auth/cloud-platform")) extends AuthPreset
  final case class SignedJwt(algorithm: String = "HS256", keyOrSecret: String = "", issuer: String = "", subject: String = "", audience: String = "", customClaimsJson: String = "{}", ttlSeconds: Long = 3600L) extends AuthPreset
  final case class Custom(headers: List[MetadataHeader]) extends AuthPreset
}

final case class AuthProfile(
  id: String,
  name: String,
  preset: AuthPreset,
  customHeaders: List[MetadataHeader] = Nil,
  description: String = "",
  isGlobal: Boolean = false
) {
  def allHeaders: List[MetadataHeader] = preset.toHeaders ++ customHeaders.filter(_.enabled)
}
```

---

### 3.4 RpcCall & Execution State (`net.protoform.core.call`)

```scala
sealed trait RpcCallStatus
object RpcCallStatus {
  case object Idle extends RpcCallStatus
  case object Connecting extends RpcCallStatus
  case object InFlight extends RpcCallStatus
  case object Streaming extends RpcCallStatus
  final case class Completed(code: Int, description: String, latencyMs: Long) extends RpcCallStatus
  final case class Failed(code: Int, description: String, cause: Option[String] = None) extends RpcCallStatus
}

final case class StreamChunk(
  index: Long,
  timestampMs: Long,
  payloadJson: String
)

final case class RpcCall(
  id: CallId = CallId.random(),
  target: EndpointTarget,
  method: MethodSchema,
  status: RpcCallStatus,
  requestJson: String,
  responseHeaders: Map[String, String] = Map.empty,
  responseTrailers: Map[String, String] = Map.empty,
  responseBodyJson: Option[String] = None,
  streamMessages: List[StreamChunk] = List.empty,
  latencyMs: Option[Long] = None
)
```

---

### 3.5 Workspace & Persistence Models (`net.protoform.core.workspace`)

These persistence models live in `core/workspace/WorkspaceModels.scala` (with `WorkspacePort` strictly trait-only in `ports/storage/`):

```scala
final case class AppSettings(
  theme: String = "ProtoForm Carbon",
  showDefaultValues: Boolean = true,
  preserveProtoFieldNames: Boolean = true,
  autoFormatJson: Boolean = true,
  autoSavePayloads: Boolean = true,
  autoSelectLastMethod: Boolean = true,
  lastEndpoint: String = "localhost:50051",
  lastUseTls: Boolean = false,
  lastSelectedMethod: Option[String] = None,
  lastAuthProfileId: Option[String] = None,
  requestDeadlineSeconds: Int = 0,
  reflectionTimeoutSeconds: Int = 15,
  maxInboundMessageMb: Int = 32,
  useGzipCompression: Boolean = false,
  insecureSkipTlsVerify: Boolean = false,
  customCaCertPath: Option[String] = None,
  clientCertPath: Option[String] = None,
  clientKeyPath: Option[String] = None,
  protocPath: Option[String] = None,
  protoIncludeDirs: List[String] = Nil,
  editorFontSize: Int = 13,
  windowWidth: Double = 1480.0,
  windowHeight: Double = 920.0,
  windowX: Option[Double] = None,
  windowY: Option[Double] = None,
  isMaximized: Boolean = false,
  splitDividerPositions: List[Double] = List(0.25, 0.62),
  requestResponseVertical: Boolean = false,
  showNavPane: Boolean = true,
  showResponsePane: Boolean = true,
  requestTabOrder: List[String] = List("form", "json", "auth"),
  responseTabOrder: List[String] = List("resp-body", "resp-headers", "resp-stream"),
  navTabOrder: List[String] = List("services", "templates", "history")
)

final case class ServiceTemplate(
  id: String,
  name: String,
  description: String = "",
  serviceFullName: String,
  methodName: String,
  requestJson: String,
  authPresetType: String = "None",
  authPresetData: Map[String, String] = Map.empty,
  customHeaders: List[MetadataHeader] = Nil,
  tags: List[String] = Nil,
  expectedStatus: Option[String] = None,
  createdAt: Long = System.currentTimeMillis(),
  updatedAt: Long = System.currentTimeMillis()
) {
  def fullMethodName: String = s"$serviceFullName/$methodName"
}

final case class CallHistoryEntry(
  id: String,
  fingerprint: String,
  endpoint: String,
  useTls: Boolean = false,
  serviceFullName: String,
  methodName: String,
  requestJson: String,
  authPresetType: String = "None",
  authPresetData: Map[String, String] = Map.empty,
  customHeaders: List[MetadataHeader] = Nil,
  statusCode: Int,
  statusDescription: String,
  latencyMs: Long,
  responsePreview: String = "",
  responseBodyJson: String = "",
  executedAt: Long = System.currentTimeMillis()
)

final case class EnvironmentConfig(
  id: String,
  name: String,
  endpoint: String = "localhost:50051",
  useTls: Boolean = false,
  authProfileId: Option[String] = None,
  insecureSkipTlsVerify: Boolean = false,
  customCaCertPath: Option[String] = None,
  clientCertPath: Option[String] = None,
  clientKeyPath: Option[String] = None,
  variables: Map[String, String] = Map.empty
)
```

**Persistence Invariants:**
- `methodPayloads` caches user input per `fullMethodName` and restores it when returning to that method.
- `templates` are QA-ready request snapshots (payload, auth preset, custom headers, tags, expected status) restorable or runnable in one click.
- `callHistory` stores up to 30 entries deduplicated via `CallFingerprint.compute(...)` (SHA-256 over endpoint, TLS, method, whitespace-normalized JSON, auth, and active headers), updating repeated runs in-place and bubbling them to index 0.
- `LocalJsonWorkspaceRepository` writes atomically via a sibling `.tmp` file + `ATOMIC_MOVE` (with `REPLACE_EXISTING` fallback).

---

## 4. Domain Invariants & Rules

1. **Protobuf Value Range Invariants**: Numeric values entered in forms must satisfy their protobuf types (`Int32`, `UInt32`, `Int64`, etc.).
2. **Metadata Key Validity**: Keys are trimmed and lowercased (`Locale.ROOT`) before gRPC attachment; invalid RFC 7540/gRPC keys are safely skipped without crashing the call.
3. **Stream Cancellation Integrity**: Cancelling a Server, Client, or Bidi stream immediately cancels the underlying `ClientCall` and removes it from active call tracking.
4. **Default Value Retention**: Serializing form fields or `DynamicMessage` payloads preserves default values and `snake_case` proto field names unless explicitly disabled.

---

## 5. Domain Error Hierarchy (`net.protoform.core.error`)

```scala
sealed abstract class DomainError(
  val message: String,
  val cause: Option[Throwable] = None
) extends Exception(message, cause.orNull)

object DomainError {
  final case class EndpointUnreachable(target: EndpointTarget, reason: String, underlying: Option[Throwable] = None)
    extends DomainError(s"Cannot reach ${target.displayLabel}: $reason", underlying)

  final case class ReflectionNotSupported(target: EndpointTarget, detail: String)
    extends DomainError(s"Server reflection failed on ${target.displayLabel}: $detail")

  final case class MethodNotFound(service: String, method: String)
    extends DomainError(s"Method $service/$method was not found in schema.")

  final case class PayloadParsingFailed(rawInput: String, reason: String)
    extends DomainError(s"Failed to parse input payload: $reason")

  final case class RpcExecutionFailed(statusDescription: String, code: Int, causeDetail: Option[String] = None)
    extends DomainError(s"gRPC call failed with code $code: $statusDescription")

  final case class StorageFailure(operation: String, path: String, detail: String)
    extends DomainError(s"Storage error during $operation at $path: $detail")

  final case class SchemaParseError(source: String, detail: String)
    extends DomainError(s"Failed to load schema from $source: $detail")
}
```
