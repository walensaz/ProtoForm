# ProtoForm — Engineering Methodology, Philosophy & Standards

This document establishes the software engineering principles, agent-first clean code standards, reflection mechanics, distribution strategy, and backend roadmap governing **ProtoForm**.

---

## 1. Architectural Philosophy & Guiding Principles

ProtoForm is designed around two core values: **Absolute Developer Transparency** and **Robust Architectural Decoupling**.

In many gRPC tools, users face empty text boxes, must hunt down `.proto` files in Git repositories, or are confused when default values (`0`, `false`, `""`) vanish from serialized messages. ProtoForm treats gRPC Server Reflection as a first-class citizen and makes the full schema visible and editable immediately.

---

## 2. SOLID Principles in Practice

### 2.1 Single Responsibility Principle (SRP)
- `GrpcServerReflectionAdapter` only discovers services and resolves `FileDescriptorProto` graphs over the reflection stream.
- `DynamicFormView` only renders JavaFX control trees from `FieldSchema` specifications; it never dispatches network calls.
- `DynamicCallAdapter` only executes gRPC calls using `DynamicMessage` and marshals request/response JSON and headers/trailers.
- `MetadataAuthInterceptor` only intercepts gRPC calls to attach HTTP/2 metadata headers.

### 2.2 Open/Closed Principle (OCP)
- The form engine uses a polymorphic `FieldInputControl` abstraction (`FormControls.scala`) so new widgets (timestamp pickers, UUID generators, `oneof` switchers) can be added without modifying traversal logic:
```scala
trait FieldInputControl {
  def getNode: javafx.scene.Node
  def getValueJson: String
  def resetToDefault(): Unit
}
```

### 2.3 Liskov Substitution Principle (LSP)
- Unary and streaming executions conform to the unified `DynamicCallPort` and `ActiveClientStream` contracts without breaking caller invariants.

### 2.4 Interface Segregation Principle (ISP)
- Ports are segregated into focused capabilities rather than a monolithic client trait:
  - `GrpcChannelPort`: channel lifecycle and pooling.
  - `ServerReflectionPort`: service discovery and descriptor resolution.
  - `DynamicCallPort`: dynamic RPC execution and cancellation.
  - `WorkspacePort`: local workspace persistence.

### 2.5 Dependency Inversion Principle (DIP)
- Application services (`ConnectionService`, `ReflectionService`, `InvocationService`, `WorkspaceService`) depend on domain models and port traits, never concrete Netty or filesystem implementations.

---

## 3. DRY (Don't Repeat Yourself) Standards

1. **Centralized Schema & Defaults**: Schema traversal and scalar default values derive from the canonical `ProtoType` / `FieldSchema` domain AST (`ProtoType.defaultScalarValue`).
2. **Unified Metadata Pipeline**: All auth presets (`ApiKey`, `BearerToken`, `BasicAuth`, `Custom`) convert to `List[MetadataHeader]`, feeding a single `MetadataAuthInterceptor`.
3. **Shared Stream & Formatting Helpers**: `DynamicCallAdapter` shares `JsonFormat` printer/parser configuration, `ActiveClientStream` closed-state handling, and header/trailer capture across RPC patterns; `FormControls.scala` reuses scalar widgets across standalone, repeated, and map fields.

---

## 4. Protobuf Reflection & Dynamic RPC Mechanics

### 4.1 Server Reflection (`v1` with `v1alpha` Fallback)
`GrpcServerReflectionAdapter` implements both `grpc.reflection.v1.ServerReflection` (primary) and `grpc.reflection.v1alpha.ServerReflection` (automatic fallback on `Status.Code.UNIMPLEMENTED`):
1. Opens a bidirectional `serverReflectionInfo` stream with a non-blocking `Promise` and bounded timeout that cancels the open `ClientCallStreamObserver` on expiry.
2. Sends `list_services = ""` and filters out `grpc.reflection.*` internal services.
3. For each discovered service, requests `file_containing_symbol = serviceName` and correlates responses using `original_request`.
4. For any missing transitive `.proto` import not bundled in the initial response or well-known Google proto fallback map, issues cycle-guarded `file_by_filename` requests.
5. Topologically builds `Descriptors.FileDescriptor` instances via `FileDescriptor.buildFrom(...)` and assembles `List[ServiceSchema]`.

### 4.2 Dynamic RPC Calls Without Compiled Stubs
ProtoForm constructs runtime method descriptors via `DescriptorRegistry` (keyed by opaque `SchemaHandle` + full message name) and `io.grpc.protobuf.ProtoUtils`:

```scala
val inputDesc  = descriptorRegistry.resolveMessage(method.schemaHandle, method.inputSchema.fullName).get
val outputDesc = descriptorRegistry.resolveMessage(method.schemaHandle, method.outputSchema.fullName).get

val methodDesc: io.grpc.MethodDescriptor[DynamicMessage, DynamicMessage] =
  io.grpc.MethodDescriptor.newBuilder[DynamicMessage, DynamicMessage]()
    .setType(methodType)
    .setFullMethodName(MethodDescriptor.generateFullMethodName(method.serviceFullName, method.name))
    .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(inputDesc)))
    .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(outputDesc)))
    .build()

val call = interceptedChannel.newCall(methodDesc, CallOptions.DEFAULT)
ClientCalls.asyncUnaryCall(call, requestDynamicMessage, responseObserver)
```

### 4.3 Interactive Streaming Mechanics
- **Server Streaming**: Sends the initial `DynamicMessage` and streams `StreamChunk` callbacks to the UI log without blocking the FX thread.
- **Client Streaming**: Returns a synchronized `ActiveClientStream` for sequential `sendNext(json)` calls, `complete()`, or `cancel()`, delivering the final unary `RpcCall`.
- **Bidirectional Streaming**: Concurrently dispatches user chunks while streaming incoming server chunks via `onChunk` / `onComplete`.

---

## 5. Default Values Philosophy & Implementation

Protobuf v3 omits zero-value fields on the wire and in default JSON output, which hides optional flags and filters during exploration. ProtoForm preserves visibility in two ways:
1. **Dynamic Form Pre-population**: Visual controls initialize with `ProtoType.defaultScalarValue` and enum default values.
2. **JSON Format Preservation**: `FormSchemaService` and `DynamicCallAdapter` use:
   ```scala
   JsonFormat.printer()
     .includingDefaultValueFields()
     .preservingProtoFieldNames()
     .print(dynamicMessage)
   ```
   `FormSchemaService` guards cyclic/recursive messages (visited set + max depth 8), respects `oneof` exclusivity (`getRealContainingOneof`), and falls back safely for well-known types like unset `google.protobuf.Value`.

---

## 6. Standalone Fat JAR Distribution & Launcher Trick

Launching a JavaFX 17+ `Application` subclass directly via `java -jar` fails because JavaFX is not on the JVM boot module layer. ProtoForm solves this with `net.protoform.Launcher`:

```scala
package net.protoform

object Launcher {
  def main(args: Array[String]): Unit = {
    ProtoFormApp.main(args)
  }
}
```

Setting `assembly / mainClass := Some("net.protoform.Launcher")` in `build.sbt` lets the shaded fat JAR run anywhere with a plain `java -jar`.

---

## 7. User-Only Security & Sandboxing

1. **No System Directory Writes**: Persists exclusively under `~/.protoform/workspace.json` using atomic sibling `.tmp` writes.
2. **Standard User-Space Process**: Requires zero administrative privileges (no UAC / `sudo`).
3. **Outbound Network Only**: Opens only user-initiated outbound gRPC connections.

---

## 8. State Persistence, Templates & Auth Store

- **Method Field Memory (`methodPayloads`)**: Auto-caches request JSON per `serviceFullName/methodName` and restores it when revisiting the method.
- **Service Templates (`ServiceTemplate`)**: Named, searchable request snapshots capturing payload JSON, auth preset, and custom headers.
- **Reusable Auth Store (`AuthProfile`)**: Global named credential profiles applicable across any service/method.

---

## 9. Tactile Desktop UI & Modularity

- **Precision Instrumentation**: Solid high-contrast accent primary actions and dedicated green `-pf-run` execute triggers (≥ 4.5:1 white-text contrast including `:hover`), solid elevated secondary surfaces, explicit dropdown button caps, unified segmented control pills (`.target-pill-box`, `.method-pill-box`), and semantic crimson/emerald states—no hollow wireframe buttons or generic neon-purple SaaS glow.
- **2-Pane Progressive Default & Tactile Message Grouping**: Launches in a symmetric 2-pane Request | Response layout (aligned `48px` `.pane-header-bar`s) with a `40px` collapsible `ActivityRail` (`Services`, `Templates`, `History`), grouping fields inside a cohesive `.message-object-card` (`1px`-divided rows) and exposing inline power-tool buttons via `Show Advanced Toolbar Actions`.
- **Typography & Swapped Theme Stylesheets**: Bundled `IBM Plex Sans` and `JetBrains Mono` with `-fx-font-smoothing-type: gray`; `ThemeManager` swaps `Scene` stylesheets (`tokens.css` → `theme-*.css` → `custom.css`) so popups, dialogs, and floating stages inherit tokens automatically.
- **Frameless Chrome & Modular Panes**: `StageStyle.UNDECORATED` with unified `CustomTitleBar` (`46px`), `WindowResizeHelper`, and `400ms` debounced window/divider persistence; views stay decoupled from the primary `Stage` via `AppState` to enable IntelliJ-style tab reordering, splits, and floating windows.

---

## 10. Agent-First Clean Code Standards

ProtoForm is developed collaboratively by engineers and AI coding agents. Code must be predictable, scannable, and low-noise:

1. **Explicit Scala 3 Braces Syntax (`-no-indent`)**:
   - Always use `{ ... }` for classes, traits, objects, enums, extension blocks, methods, and control structures.
   - Never use Python-style significant indentation (`:` / `end` markers). Enforced at compile time via `-no-indent` in `build.sbt`.
2. **Small, Cohesive Files**:
   - Keep files focused on one responsibility (~≤250–300 LOC). Split growing adapters, views, or parsers into dedicated helper files rather than letting god-classes accumulate.
3. **DRY Without Over-Abstraction**:
   - Extract shared helpers when logic repeats across 2+ call sites (e.g. JSON serialization, stream lifecycle, Gson null-safe extraction), while avoiding speculative generic frameworks.
4. **Minimal, Why-Only Comments**:
   - Do not narrate *what* the code does (`// Settings`, `// Parse JSON`, `// Return result`).
   - Write brief comments only for *why*: non-obvious invariants, protocol quirks (e.g. IPv6 bracketing for Netty, `v1alpha` fallback semantics), or concurrency guarantees.
5. **Strict Hygiene & Focused Tests**:
   - Zero unused imports, dead variables, or swallowed exceptions without logging/handling.
   - Pair every domain rule, adapter, and bug fix with fast, deterministic ScalaTest specs (using isolated temp dirs and ephemeral `NettyServerBuilder.forPort(0)` servers).

---

## 11. Prioritized Backend Backlog & Roadmap

Tracks backend capabilities and architectural follow-ups (see `COMPONENTS.md` for the UI roadmap):

### Completed Milestones
- **Workspace DTO Separation (`core/workspace/`)**: Moved `AppSettings`, `ServiceTemplate`, `CallHistoryEntry`, `SavedRequest`, and `WorkspaceData` out of `ports/storage/WorkspacePort.scala` into `core.workspace`.
- **Offline `.desc` / `.binpb` (`FileDescriptorSet`) Loader**: Implemented `SchemaParserPort` + `DescriptorSetSchemaAdapter` + `SchemaService` sharing `ProtobufSchemaConverter`.
- **Per-Call Deadlines, `gzip` Compression & Max Inbound Size**: Added `RpcCallOptions` across `DynamicCallPort`, `InvocationService`, and `DynamicCallAdapter`, plus configurable `maxInboundMessageMb` in `NettyGrpcChannelAdapter`.
- **Dev Insecure TLS, Custom CA & mTLS Client Certificates**: Added `insecureSkipTlsVerify`, custom CA PEM, and mTLS `clientCertPath`/`clientKeyPath` (`keyManager`) in `EndpointTarget` and `NettyGrpcChannelAdapter`.
- **`grpcurl` Export & Import, gRPC Health Probe & Deduplicated Call History**: Implemented `Copy as grpcurl`, `GrpcurlParser` CLI import, `ConnectionService.checkHealth` (`grpc.health.v1.Health/Check`), and 30-entry SHA-256 deduplicated `CallHistoryEntry` with bounded `responseBodyJson`.
- **P0 — Full `core.schema` Descriptor Decoupling**: Removed `com.google.protobuf.Descriptors` from `ServiceSchema` and `MethodSchema` via `SchemaHandle` and infrastructure `DescriptorRegistry`; migrated `FormSchemaService` and `DynamicFormView` onto pure `ProtoType.MessageType`/`FieldSchema`.
- **P1 — Raw `.proto` Source Loader**: Added `ProtoSourceParserPort` and `ProtocProtoSchemaAdapter` (`protoc --descriptor_set_out --include_imports -I...`) with remembered `-I` include roots.
- **P2 — Named Environments, QA Suite Runner, Response Diffing, Stream Replay & Well-Known Widgets**: Implemented `EnvironmentConfig` + `VariableInterpolator` (`{{var}}`), `SuiteRunnerService` (`expectedStatus` assertions + Markdown/JSON reports), `JsonDiff` (LCS line diff + structural JSON deltas), `StreamScriptParser`/`StreamScriptPane`, and `WellKnownTypeHelper`/controls.
- **1.0.0 Release Packaging & Python Code Export**: Added `PythonSnippetGenerator` (`Stubs` + `Reflection`), `GrpcurlFormatter`, `CodeExportDialog`, multi-resolution app icons (`packaging/generate_icons.py` + `ThemeManager.applyStageIcons`), reduced-JRE `jpackage` scripts (`packaging/package-windows.ps1`, `packaging/package-unix.sh`), `curl`/PowerShell installers (`packaging/install.sh`, `packaging/install.ps1`), `.github/workflows/release.yml`, `README.md`, and GPL-3.0 `LICENSE`.

### Future Exploration
- **Pure-JVM `.proto` Parser Option**: Optional fallback when `protoc` is absent on `PATH`.
- **Workspace Encryption / OS Keychain Integration**: Encrypting sensitive `AuthProfile` and environment secret variables at rest.
