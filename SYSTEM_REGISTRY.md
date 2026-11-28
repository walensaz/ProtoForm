# ProtoForm — System Registry & Component Inventory

This registry tracks the component inventory, source file paths, architectural responsibilities, port-adapter mappings, and unit test suites for **ProtoForm**.

---

## 1. Architectural Layers

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             PROTOFORM ARCHITECTURE                            │
│                                                                             │
│  Presentation Layer: UI Views, Custom Dialogs, AtlantaFX Theme, Form Engine │
│  ─────────────────────────────────────────────────────────────────────────  │
│  Application Layer:  Connection, Reflection, Schema, Invocation, Form, Ws   │
│  ─────────────────────────────────────────────────────────────────────────  │
│  Domain Layer:       EndpointTarget, Schema AST, Auth, RpcCall, Workspace   │
│  ─────────────────────────────────────────────────────────────────────────  │
│  Ports Layer:        GrpcChannelPort, ServerReflectionPort, SchemaParserPort│
│                      DynamicCallPort, WorkspacePort                         │
│  ─────────────────────────────────────────────────────────────────────────  │
│  Adapters Layer:     NettyChannel, Reflection (v1/v1alpha), DescriptorSet,  │
│                      DynamicCall, MetadataAuthInterceptor, JsonWorkspace    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Directory & File Inventory

### 2.1 Core Domain Layer (`src/main/scala/net/protoform/core/`)

| Component / Class | File Location | Responsibility |
|---|---|---|
| `EndpointTarget` | `core/model/EndpointTarget.scala` | Value object modeling host, port, TLS, mTLS (`clientCertPath`, `clientKeyPath`), authority, CA cert path, `insecureSkipTlsVerify`, IPv6-safe formatting, and `parse(...)`. |
| `KubeTargetSpec`, `ActivePortForward`, `KubeTargetCandidate` | `core/model/KubeModels.scala` | Pure domain models for Kubernetes Services/Pods, port-forward target specs, and active local tunnel state. |
| `TargetId`, `CallId` | `core/model/Identifiers.scala` | Strongly-typed opaque identifiers for targets and calls. |
| `ServiceSchema`, `SchemaHandle` | `core/schema/ServiceSchema.scala`, `SchemaHandle.scala` | Pure Scala model of a gRPC service, package, methods, optional `protoFile`, and opaque `SchemaHandle` (zero Protobuf imports). |
| `MethodSchema`, `RpcPattern` | `core/schema/MethodSchema.scala` | Pure Scala model of an RPC method, streaming pattern enum, I/O `ProtoType.MessageType` schemas, and `SchemaHandle`. |
| `FieldSchema` | `core/schema/FieldSchema.scala` | Represents individual protobuf fields with default-value, `isOptional` (`proto3_optional`), and `oneof` metadata. |
| `ProtoType` | `core/schema/ProtoType.scala` | Sealed ADT for scalars, `EnumValue`/`EnumType`, lazy-thunk `MessageType`, and `MapType` (with `simpleName` helpers). |
| `AuthPreset`, `AuthProfile`, `MetadataHeader` | `core/auth/AuthPreset.scala` | Models `None`, `ApiKey`, `BearerToken`, `BasicAuth`, `GoogleCloud`, `SignedJwt`, `Custom`, project/global reusable profiles, and metadata headers. |
| `RpcCall`, `RpcCallStatus`, `StreamChunk`, `StreamDirection` | `core/call/RpcCall.scala` | Aggregate tracking request/response JSON, latency, headers/trailers, status (`InFlight`, etc.), and directional stream chunks. |
| `RpcCallOptions` | `core/call/RpcCallOptions.scala` | Per-call execution options (`deadlineSeconds`, `useGzipCompression`, `includeDefaultFields`, `preserveProtoFieldNames`). |
| `GrpcurlParser`, `ParsedGrpcurlCommand` | `core/call/GrpcurlParser.scala` | Pure Scala shell tokenizer and `grpcurl` CLI command parser (flags, mTLS, headers, Bearer/Basic extraction, IPv6, payload). |
| `PythonSnippetGenerator`, `GrpcurlFormatter` | `core/call/PythonSnippetGenerator.scala` | Pure Scala generators for `grpcio` stub-based (`*_pb2` + `ParseDict`) and dynamic `grpcio-reflection` Python clients plus `grpcurl` CLI formatting. |
| `JsonDiff`, `DiffKind`, `DiffLine`, `JsonFieldDelta` | `core/call/JsonDiff.scala` | Pure line-level LCS diff and recursive JSON structural delta engine. |
| `WellKnownTypeHelper`, `StreamScriptParser` | `core/call/WellKnownAndStreamHelpers.scala` | Timestamp/Duration normalization & validation and JSONL/JSON-array stream script parsing. |
| `AppSettings`, `WorkspaceProject`, `SavedWorkbenchTab`, `ProjectExportBundle`, `EnvironmentConfig`, `ServiceTemplate`, `CallHistoryEntry`, `SavedRequest`, `WorkspaceData`, `AuthSerialization`, `CallFingerprint` | `core/workspace/WorkspaceModels.scala` | Pure Scala multi-project workspace persistence models (global settings & auth profiles, per-project foldered templates, `lastTemplateByMethod`, environments, workbench tabs, and 30-entry history), auth serialization, and SHA-256 history fingerprinting. |
| `VariableInterpolator`, `InterpolationResult` | `core/workspace/VariableInterpolator.scala` | Pure `{{var}}` interpolation across endpoints, auth presets, metadata headers, and JSON payloads with unresolved-key detection. |
| `DomainError` | `core/error/DomainError.scala` | Sealed exception hierarchy for connection, reflection, schema parsing, payload parsing, execution, and storage errors. |

---

### 2.2 Ports Layer (`src/main/scala/net/protoform/ports/`)

| Port Trait | File Location | Pattern | Responsibility |
|---|---|---|---|
| `GrpcChannelPort` | `ports/rpc/GrpcChannelPort.scala` | Outbound Port | Creation, pooling/reuse (including `maxInboundMessageMb`), and teardown of gRPC `ManagedChannel`s. |
| `ServerReflectionPort` | `ports/reflection/ServerReflectionPort.scala` | Outbound Port | Discovers services and resolves protobuf descriptors into `List[ServiceSchema]` with configurable timeout. |
| `SchemaParserPort` | `ports/schema/SchemaParserPort.scala` | Outbound Port | Offline `.desc` / `.binpb` (`FileDescriptorSet`) loader into `List[ServiceSchema]`. |
| `ProtoSourceParserPort` | `ports/schema/ProtoSourceParserPort.scala` | Outbound Port | Compiles raw `.proto` source files and `-I` include roots into `List[ServiceSchema]`. |
| `DynamicCallPort`, `ActiveClientStream` | `ports/rpc/DynamicCallPort.scala` | Outbound Port | Executes Unary, Server, Client, and Bidi streaming calls using JSON, `MethodSchema`, and `RpcCallOptions`. |
| `WorkspacePort` | `ports/storage/WorkspacePort.scala` | Outbound Port | Persistence contract for `WorkspaceData`. |
| `DynamicAuthTokenPort` | `ports/auth/DynamicAuthTokenPort.scala` | Outbound Port | Resolves dynamic auth credentials (`GoogleCloud`, `SignedJwt`) into gRPC metadata headers. |
| `KubePortForwardPort` | `ports/infra/KubePortForwardPort.scala` | Outbound Port | Discovers Kubernetes contexts/namespaces/targets and manages local port-forward tunnels. |

---

### 2.3 Application Layer (`src/main/scala/net/protoform/application/`)

| Service | File Location | Dependencies | Responsibility |
|---|---|---|---|
| `ConnectionService` | `application/ConnectionService.scala` | `GrpcChannelPort` | Lifecycle manager for active server connections, `grpc.health.v1.Health` probe, and shutdown. |
| `ReflectionService` | `application/ReflectionService.scala` | `ServerReflectionPort` | Delegates server reflection introspection with configurable timeout (`introspect`). |
| `SchemaService` | `application/SchemaService.scala` | `SchemaParserPort`, `ProtoSourceParserPort` | Loads offline Protobuf descriptor sets (`loadDescriptorSet`) and raw `.proto` source files (`loadProtoFiles`). |
| `InvocationService` | `application/InvocationService.scala` | `DynamicCallPort` | Orchestrates Unary, Server, Client, Bidi calls (`RpcCallOptions`) and cancellation. |
| `FormSchemaService` | `application/FormSchemaService.scala` | `ProtoType.MessageType`, `FieldSchema`, `Gson` | Pure-AST default JSON generation (honoring `includeDefaultFields`, `preserveProtoFieldNames`, cycle depth 8, `oneofGroup` exclusivity, well-known types) and JSON formatting. |
| `SuiteRunnerService` | `application/SuiteRunnerService.scala` | `InvocationService`, `VariableInterpolator` | Sequential tag-filtered `ServiceTemplate` QA suite execution against `expectedStatus` and Markdown/JSON report export. |
| `WorkspaceService` | `application/WorkspaceService.scala` | `WorkspacePort` | Thread-safe (`AtomicReference` + serialized save lock) manager for settings, environments, recent endpoints, method payloads, QA templates, auth profiles, session auth, and 30-entry deduplicated call history. |

---

### 2.4 Infrastructure Layer / Outbound Adapters (`src/main/scala/net/protoform/infrastructure/`)

| Adapter | File Location | Implements Port | Library / Technology |
|---|---|---|---|
| `NettyGrpcChannelAdapter` | `infrastructure/grpc/NettyGrpcChannelAdapter.scala` | `GrpcChannelPort` | `NettyChannelBuilder` with atomic channel pooling keyed by `(uriString, overrideAuthority, tlsCertPath, insecureSkipTlsVerify, clientCertPath, clientKeyPath, maxInboundBytes)`, custom CA, mTLS `keyManager`, dev insecure TLS, and bounded shutdown. |
| `GrpcServerReflectionAdapter` | `infrastructure/reflection/GrpcServerReflectionAdapter.scala` | `ServerReflectionPort` | `grpc.reflection.v1` primary with `v1alpha` fallback on `UNIMPLEMENTED`, transitive `file_by_filename` resolution, and non-blocking timeout/cancellation. |
| `ProtobufSchemaConverter` | `infrastructure/reflection/ProtobufSchemaConverter.scala` | Shared Helper | Topological `FileDescriptor` builder, well-known proto fallbacks, `DescriptorRegistry` registration, and pure `ServiceSchema`/`MethodSchema`/`ProtoType` AST conversion. |
| `DescriptorRegistry` | `infrastructure/schema/DescriptorRegistry.scala` | Infrastructure Registry | Thread-safe registry mapping `SchemaHandle` -> message `Descriptors.Descriptor`s (including nested/transitive types) and `TypeRegistry` for `google.protobuf.Any`. |
| `DescriptorSetSchemaAdapter` | `infrastructure/schema/DescriptorSetSchemaAdapter.scala` | `SchemaParserPort` | Parses compiled `FileDescriptorSet` (`.desc` / `.binpb`) files into `List[ServiceSchema]` and registers descriptors. |
| `ProtocProtoSchemaAdapter` | `infrastructure/schema/ProtocProtoSchemaAdapter.scala` | `ProtoSourceParserPort` | Invokes local `protoc` (`--descriptor_set_out --include_imports -I...`) with injectable command runner and delegates to `DescriptorSetSchemaAdapter`. |
| `DynamicCallAdapter` | `infrastructure/grpc/DynamicCallAdapter.scala` | `DynamicCallPort` | `ClientCalls` + `DynamicMessage` (resolved via `DescriptorRegistry`), deadlines, `gzip` compression, `RpcCallOptions` JSON formatting, header/trailer capture, `TypeRegistry` for `Any`, and synchronized `ActiveClientStream`. |
| `MetadataAuthInterceptor` | `infrastructure/auth/MetadataAuthInterceptor.scala` | Outbound Interceptor | `ClientInterceptor` attaching trimmed/lowercased ASCII and Base64 `-bin` metadata headers with invalid-key guards. |
| `DynamicAuthResolver` | `infrastructure/auth/DynamicAuthResolver.scala` | `DynamicAuthTokenPort` | Resolves `GoogleCloud` (ADC / GCE metadata / Service Account JSON access & ID tokens with expiry caching) and `SignedJwt` (`HS256`/`RS256` compact JWS). |
| `KubectlPortForwardAdapter` | `infrastructure/kube/KubectlPortForwardAdapter.scala` | `KubePortForwardPort` | Wraps `kubectl` CLI with injectable command/process runner for context/namespace/target discovery and port-forward tunnel lifecycle. |
| `LocalJsonWorkspaceRepository` | `infrastructure/storage/LocalJsonWorkspaceRepository.scala` | `WorkspacePort` | Gson JSON persistence in injectable `configDir` (default `~/.protoform/workspace.json` with one-time copy migration from legacy `~/.flexrpc/workspace.json`) with atomic `.tmp` + `ATOMIC_MOVE` writes, legacy theme normalization, multi-project migration, project bundle export/import, foldered templates, history, and null/type-safe parsing. |

---

### 2.5 Presentation Layer / JavaFX UI (`src/main/scala/net/protoform/`)

| UI Component | File Location | Responsibility |
|---|---|---|
| `Launcher` | `Launcher.scala` | Static main entry point for standalone fat-JAR execution without `--module-path`. |
| `ProtoFormApp` | `ProtoFormApp.scala` | JavaFX primary stage setup, 2-pane default split + collapsible rail/drawer, live debounced resize/divider persistence, drag-and-drop tab persistence, workspace & environment switching, shortcuts (`Ctrl+Enter`, `Ctrl+K`, `Ctrl+B`, `Ctrl+1/2/3/4`, `Ctrl+L`, `Ctrl+T`, `Ctrl+W`, `Ctrl+Tab`, `Ctrl+,`), and lifecycle coordinator. |
| `CustomTitleBar` | `ui/views/CustomTitleBar.scala` | Unified `46px` JavaFX 25 `HeaderBar` integrating native OS traffic lights / caption buttons with Quick Settings menu (`>_ ProtoForm`), `[Workspace: <name> ▾]` menu, `TargetBarControl`, Environment Switcher, Jump, Response toggle, and Settings. |
| `TargetBarControl` | `ui/views/TargetBarControl.scala` | Flush 4-segment Target Pill (`TLS` lock toggle, editable endpoint combo, `Refl`/schema menu for Reflection/`.desc`/`.proto`/Kubernetes Port-Forward/Health, and icon-only `↻` Introspect button) embedded in `CustomTitleBar`. |
| `KubePortForwardDialog` | `ui/views/KubePortForwardDialog.scala` | Custom-chromed `kubectl` context/namespace/service/pod browser and active port-forward tunnel manager. |
| `WorkbenchTabBar` | `ui/views/WorkbenchTabBar.scala` | `34px` multi-tab workbench strip (`[+]`, close `×`, streaming dot, dirty dot, middle-click & context menu) swapping `WorkbenchTabSession`s cleanly. |
| `ActivityRail` | `ui/views/ActivityRail.scala` | Slim `40px` left rail toggling `Services` (with connected indicator dot), `Templates` (with count badge), `History`, `Workspaces`, and `Suite` runner shortcut. |
| `SearchableSelector` | `ui/views/SearchableSelector.scala` | Reusable themed popup selector with explicit right-side dropdown cap, live text filtering, and keyboard navigation for large Service and Method lists. |
| `SettingsDialog` | `ui/views/SettingsDialog.scala` | Custom-chromed searchable Settings dialog with left category sidebar, mTLS cert/key & `protoc` path pickers, and context-aware dirty-tracked `Save Settings`. |
| `EnvironmentManagerDialog` | `ui/views/EnvironmentManagerDialog.scala` | Custom-chromed manager for named environments (`local`/`staging`/`qa`/`prod`), TLS/mTLS overrides, linked `AuthProfile`, and `{{var}}` variables. |
| `ProtoImportDialog` | `ui/views/ProtoImportDialog.scala` | Custom-chromed `.proto` multi-file and `-I` include directory picker for `protoc` schema loading. |
| `CodeExportDialog` | `ui/views/CodeExportDialog.scala` | Custom-chromed code export modal with live preview, Clipboard Copy, and Save File for Python (`grpcio` stubs / `grpcio-reflection`) and `grpcurl`. |
| `GrpcurlImportDialog` | `ui/views/GrpcurlImportDialog.scala` | Custom-chromed dialog parsing pasted `grpcurl` commands into endpoint, TLS/mTLS, auth/headers, method, and payload. |
| `SuiteRunnerDialog` | `ui/views/SuiteRunnerDialog.scala` | Tag-filtered QA batch `ServiceTemplate` runner with progress bar, pass/fail assertions, request/response inspector, and Markdown/JSON report export. |
| `ResponseDiffDialog` | `ui/views/ResponseDiffDialog.scala` | Side-by-side line diff and structural JSON field delta viewer across pinned baselines, live responses, and call history runs. |
| `QuickSwitcherDialog` | `ui/views/QuickSwitcherDialog.scala` | Custom-chromed `Ctrl+K` fuzzy Quick Jump dialog across discovered methods, QA templates, call history, workspaces, `Create New Workspace…`, and `Open QA Suite Runner` actions. |
| `NavigationPane` | `ui/views/NavigationPane.scala` | Collapsible `StackPane` drawer switching between Services filter tree, context-smart foldered QA Templates tree (`Method | Service | All`), 30-entry Call History list, and Workspaces manager (switch, `+ New`, rename, export/import bundle, delete). |
| `RequestPane` | `ui/views/RequestPane.scala` | Symmetric `48px` `.pane-header-bar` with unified `.method-pill-box` (`[Service ▾ | Method ▾]`), `•••` overflow menu (`29×29px`), dedicated green `.btn-run` `▶` button, contextual streaming bar, actionable cold-start empty state, bi-directional Form ↔ JSON sync, `StreamScriptPane`, `{{var}}` interpolation, and execution. |
| `StreamScriptPane` | `ui/views/StreamScriptPane.scala` | Inline JSONL / JSON-array stream script replay engine with configurable delay, `.jsonl` loader, and auto-complete for Client/Bidi streams. |
| `DynamicFormView` | `ui/forms/DynamicFormView.scala` | Pure-AST dynamic form renderer grouping fields into a unified `.message-object-card` with `.message-object-header` (`Request → Response`) and `1px`-divided `.message-field-row` items, `populateFromJson`, `edited` / Power Mode badges, `.oneof-branch-box` switcher, and recursion depth/cycle guard. |
| `FormControls`, `WellKnownControls` | `ui/forms/FormControls.scala`, `WellKnownControls.scala` | Bi-directional controls for scalars, `ToggleSwitch`, full-width enums, indexed `.collection-item-row` repeated/map rows with subtle `.row-delete-btn`, plus rich `Timestamp` (`Now` + ISO), `Duration` (unit presets), `FieldMask`, and `bytes` (`File…` Base64) widgets. |
| `JsonCodeArea`, `JsonEditorPane` | `ui/editor/JsonCodeArea.scala`, `JsonEditorPane.scala` | `RichTextFX` `CodeArea` with line-number gutter, JSON syntax highlighting, validation, and formatting. |
| `MetadataEditor` | `ui/views/MetadataEditor.scala` | Auth Profile selector, Auth Preset inputs, custom headers with `.row-delete-btn`, and session auth persistence. |
| `ResponsePane` | `ui/views/ResponsePane.scala` | Symmetric `48px` `.pane-header-bar` with short status/latency/size pills, uniform `29×29px` centered icon controls (`Copy`, `Pop Out`, `•••` for `Pin`/`Diff`/`Clear Stream`, or expanded inline buttons in Advanced mode), wrapping gRPC error summary banner, read-only `JsonCodeArea` with empty-state overlay, `HEADER`/`TRAILER` pill-badged copyable metadata table, and master-detail `Stream Log`. |
| `TabDragSupport` | `ui/util/TabDragSupport.scala` | Drag-and-drop tab reordering within and across `RequestPane` ↔ `ResponsePane` with `.tab-drop-target` feedback. |
| `WindowStateController`, `CustomDialogStage`, `DockingManager` | `ui/util/` | Native `StageStyle.EXTENDED` / `HeaderBar` bounds & maximize persistence (`820 × 560` min, post-show positioning), themed modal/confirmation (`showConfirm`) factory, and floating `ResponsePane` docking. |
| `ThemeManager`, `AppState` | `ui/theme/ThemeManager.scala`, `ui/state/AppState.scala` | Bundled `IBM Plex Sans`/`JetBrains Mono`, multi-resolution app icons (`applyStageIcons`), swapped CSS stacks across scenes, and central reactive properties (including environments and pinned baseline). |

---

### 2.6 Test Suite Inventory (`src/test/scala/net/protoform/`)

| Spec Class | File Location | Coverage Scope |
|---|---|---|
| `AuthPresetSpec` | `core/AuthPresetSpec.scala` | `AuthPreset` header generation (`ApiKey`, `BearerToken`, `BasicAuth`, `Custom`, `None`) and `AuthProfile.allHeaders`. |
| `EndpointTargetSpec` | `core/EndpointTargetSpec.scala` | `EndpointTarget.parse` for IPv4, hostnames, default ports, trailing slashes, empty-host rejection, and bracketed IPv6. |
| `GrpcurlParserSpec` | `core/GrpcurlParserSpec.scala` | `GrpcurlParser` multi-line continuations, quoted JSON/escapes, TLS/mTLS flags, `Bearer`/`Basic` extraction, IPv6, and error handling. |
| `PythonSnippetGeneratorSpec` | `core/PythonSnippetGeneratorSpec.scala` | `PythonSnippetGenerator` (`Stubs` & `Reflection` modes, streaming, TLS/mTLS, `-bin` headers, Python literals) and `GrpcurlFormatter`. |
| `JsonDiffSpec` | `core/JsonDiffSpec.scala` | `JsonDiff.diffLines` and recursive `diffJsonStructure` added/removed/changed path detection. |
| `VariableInterpolatorSpec` | `core/VariableInterpolatorSpec.scala` | `VariableInterpolator` `{{var}}` substitution across strings, `AuthPreset`, headers, regex-special values, and missing-key reporting. |
| `WellKnownAndStreamHelpersSpec` | `core/WellKnownAndStreamHelpersSpec.scala` | `WellKnownTypeHelper` duration/timestamp checks and `StreamScriptParser` JSONL + JSON array parsing. |
| `FormSchemaServiceSpec` | `application/FormSchemaServiceSpec.scala` | Pure-AST default JSON generation, `includeDefaultFields`/`preserveProtoFieldNames`, `formatJson`, cyclic messages, `Struct`/`Value`, `oneof`, `map`, `bytes`. |
| `SuiteRunnerServiceSpec` | `application/SuiteRunnerServiceSpec.scala` | Ephemeral Netty server sequential suite execution with `{{var}}` interpolation, `expectedStatus` verification, and Markdown/JSON report export. |
| `WorkspaceServiceSpec` | `application/WorkspaceServiceSpec.scala` | `initialize()`, CRUD, save-error recording, concurrent updates, 30-entry SHA-256 deduplicated `recordCallHistory`, `importTemplates`, and `updateSessionAuth`. |
| `WorkspaceRepositorySpec` | `infrastructure/WorkspaceRepositorySpec.scala` | Temp-dir round-trip for `AppSettings` (mTLS, `protoc`, tab orders), `EnvironmentConfig`, `ServiceTemplate`, `CallHistoryEntry`, `AuthProfile`, atomic writes, and malformed JSON resilience. |
| `DescriptorRegistrySpec` | `infrastructure/DescriptorRegistrySpec.scala` | `DescriptorRegistry` registration of nested/transitive descriptors, `TypeRegistry` resolution, and handle release. |
| `GrpcServerReflectionAdapterSpec` | `infrastructure/GrpcServerReflectionAdapterSpec.scala` | Ephemeral Netty server tests for `v1` reflection, `v1alpha` fallback on `UNIMPLEMENTED`, transitive `file_by_filename` deps, and timeout/cancellation. |
| `DescriptorSetSchemaAdapterSpec` | `infrastructure/DescriptorSetSchemaAdapterSpec.scala` | `FileDescriptorSet` (`.desc`) parsing with transitive dependencies and error handling on missing/invalid files. |
| `ProtocProtoSchemaAdapterSpec` | `infrastructure/ProtocProtoSchemaAdapterSpec.scala` | `protoc` CLI invocation flags (`--descriptor_set_out`, `--include_imports`, `-I`), validation, error handling, and `DescriptorRegistry` loading. |
| `DynamicCallAdapterSpec` | `infrastructure/DynamicCallAdapterSpec.scala` | Ephemeral Netty server tests for Unary, Server/Client/Bidi streaming, response headers/trailers capture, Gson error JSON, and `DescriptorRegistry` resolution. |
| `MetadataAuthInterceptorSpec` | `infrastructure/MetadataAuthInterceptorSpec.scala` | ASCII & `-bin` Base64 header attachment, key trimming/lowercasing, disabled headers, and invalid-key resilience. |
| `NettyGrpcChannelAdapterSpec` | `infrastructure/NettyGrpcChannelAdapterSpec.scala` | Channel pooling/keying by `(uriString, overrideAuthority, tlsCertPath, insecureSkipTlsVerify, clientCertPath, clientKeyPath, maxInboundBytes)`, mTLS pair validation, reuse, and shutdown. |
| `KubectlPortForwardAdapterSpec` | `infrastructure/KubectlPortForwardAdapterSpec.scala` | `kubectl` context/namespace/target JSON parsing and port-forward bound local port extraction. |
| `DynamicAuthResolverSpec` | `infrastructure/DynamicAuthResolverSpec.scala` | `HS256`/`RS256` Signed JWT structure/signing and Google Cloud error handling. |
| `WindowStateControllerSpec` | `ui/util/WindowStateControllerSpec.scala` | Multi-display screen-bounds clamping, minimum window dimensions (`820 × 560`), and off-screen coordinate recovery. |

---

### 2.7 Demo gRPC Server Subproject (`demo-server/`)

| Component / File | File Location | Responsibility |
|---|---|---|
| `DemoProtoSchema` | `demo-server/src/main/scala/net/protoform/demo/DemoProtoSchema.scala` | Builds `demo/common.proto`, `demo/sandbox.proto`, and `demo/orders.proto` `FileDescriptor`s and exports `FileDescriptorSet` (`demo-server/schemas/sandbox.desc`). |
| `GrpcServerHelpers` | `demo-server/src/main/scala/net/protoform/demo/GrpcServerHelpers.scala` | `MetadataAndLoggingInterceptor` (request header capture + response headers/trailers) and dynamic `ServerServiceDefinition` helpers with `ProtoFileDescriptorSupplier`. |
| `SandboxServiceImpl` | `demo-server/src/main/scala/net/protoform/demo/SandboxServiceImpl.scala` | `demo.sandbox.v1.SandboxService` (`Echo`, `SimulateBehavior`, `StreamEvents`, `CollectMetrics`, `InteractiveChat`). |
| `OrderServiceImpl` | `demo-server/src/main/scala/net/protoform/demo/OrderServiceImpl.scala` | `demo.orders.v1.OrderService` (`CreateOrder`, `GetOrder`, `ListOrders`, auth-guarded `GetAdminAudit`, `WatchOrderUpdates`) with seeded sample orders. |
| `DemoGrpcServer`, `DemoServerMain` | `demo-server/src/main/scala/net/protoform/demo/DemoServerMain.scala` | Netty gRPC server wiring reflection (`v1` & `v1alpha`), `grpc.health.v1.Health`, optional TLS, and CLI flags (`--host`, `--port`, `--no-reflection`, `--no-health`, `--cert`, `--key`, `--write-desc`). |
| `DemoServerSpec` | `demo-server/src/test/scala/net/protoform/demo/DemoServerSpec.scala` | Integration tests for `v1`/`v1alpha` reflection, transitive symbol lookup, health checks, unary/streaming RPCs, auth enforcement, and `.desc` export. |
| `.proto` & `.desc` Assets | `demo-server/protos/demo/*.proto`, `demo-server/schemas/sandbox.desc`, `demo-server/run-demo.bat` | Human-readable `.proto` sources, prebuilt `FileDescriptorSet`, and Windows launcher. |
