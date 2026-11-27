# ProtoForm — System Map & Architectural Diagrams

This document details the architectural boundaries, data flows, pipeline sequences, and internal subsystem communications for **ProtoForm**.

---

## 1. Domain-Driven Hexagonal Architecture (Ports & Adapters)

ProtoForm decouples JavaFX presentation, gRPC transport, server reflection, and local JSON storage into concentric layers around the domain core.

```mermaid
graph TD
    subgraph Inbound_Adapters [Presentation Tier / Inbound Adapters]
        UI_Main[ProtoFormApp JavaFX Window]
        UI_Title[CustomTitleBar & TargetBarControl]
        UI_Nav[ActivityRail & NavigationPane Drawer]
        UI_Req[RequestPane, SearchableSelector & StreamScriptPane]
        UI_Form[DynamicFormView, FormControls & WellKnownControls]
        UI_Json[JsonEditorPane & JsonCodeArea RichTextFX]
        UI_Auth[MetadataEditor View]
        UI_Resp[ResponsePane, StreamLog & ResponseDiffDialog]
        UI_State[AppState Reactive Properties]
        UI_Theme[ThemeManager & -pf-* Tokens]
    end

    subgraph Application_Layer [Application Layer / Use Cases]
        UC_Conn[ConnectionService]
        UC_Reflect[ReflectionService]
        UC_Schema[SchemaService]
        UC_Form[FormSchemaService]
        UC_Invoke[InvocationService]
        UC_Suite[SuiteRunnerService]
        UC_Workspace[WorkspaceService]
    end

    subgraph Domain_Core [Domain Layer - net.protoform.core]
        Agg_Target[EndpointTarget & TargetId/CallId]
        Agg_Call[RpcCall, RpcCallStatus, StreamChunk, JsonDiff, GrpcurlParser]

        subgraph Value_Objects [Pure Schema AST, Auth & Environments]
            VO_Service[ServiceSchema & SchemaHandle]
            VO_Method[MethodSchema & RpcPattern]
            VO_Field[FieldSchema & ProtoType]
            VO_Auth[AuthPreset, AuthProfile, MetadataHeader]
            VO_Env[EnvironmentConfig & VariableInterpolator]
        end

        Domain_Err[DomainError Hierarchy]
    end

    subgraph Ports [Outbound Ports / Traits - net.protoform.ports]
        Port_Chan[GrpcChannelPort]
        Port_Reflect[ServerReflectionPort]
        Port_Call[DynamicCallPort & ActiveClientStream]
        Port_Proto[SchemaParserPort & ProtoSourceParserPort]
        Port_Storage[WorkspacePort]
        Port_Auth[DynamicAuthTokenPort]
        Port_Kube[KubePortForwardPort]
    end

    subgraph Outbound_Adapters [Driven Adapters / Infrastructure]
        Adapter_Netty[NettyGrpcChannelAdapter]
        Adapter_Reflect[GrpcServerReflectionAdapter]
        Adapter_Call[DynamicCallAdapter]
        Adapter_Auth[MetadataAuthInterceptor & DynamicAuthResolver]
        Adapter_Kube[KubectlPortForwardAdapter]
        Adapter_Reg[DescriptorRegistry]
        Adapter_Proto[DescriptorSetSchemaAdapter & ProtocProtoSchemaAdapter]
        Adapter_JsonStore[LocalJsonWorkspaceRepository]
    end

    subgraph External_Systems [External Boundary]
        gRPC_Server[(Remote gRPC Server)]
        Local_FS[(Local Filesystem ~/.protoform)]
    end

    %% UI to Application
    UI_Title --> UC_Conn
    UI_Title --> UC_Reflect
    UI_Title --> UC_Schema
    UI_Req --> UC_Form
    UI_Req --> UC_Invoke
    UI_Req --> UC_Conn
    UI_Main --> UC_Workspace

    %% Application to Domain
    UC_Conn --> Agg_Target
    UC_Reflect --> VO_Service
    UC_Reflect --> VO_Method
    UC_Form --> VO_Field
    UC_Invoke --> Agg_Call
    UC_Workspace --> VO_Auth

    %% Application to Ports
    UC_Conn --> Port_Chan
    UC_Reflect --> Port_Reflect
    UC_Schema --> Port_Proto
    UC_Invoke --> Port_Call
    UC_Workspace --> Port_Storage

    %% Ports to Adapters
    Port_Chan --> Adapter_Netty
    Port_Reflect --> Adapter_Reflect
    Port_Call --> Adapter_Call
    Port_Proto --> Adapter_Proto
    Port_Storage --> Adapter_JsonStore

    %% Adapter composition & External
    Adapter_Call --> Adapter_Auth
    Adapter_Netty -->|HTTP/2 TLS or Plaintext| gRPC_Server
    Adapter_Reflect -->|grpc.reflection.v1 -> v1alpha fallback| gRPC_Server
    Adapter_Call -->|DynamicMessage RPC| gRPC_Server
    Adapter_JsonStore -->|Atomic JSON Persistence| Local_FS
```

---

## 2. Server Reflection & Service Discovery Sequence

When a user enters an endpoint (e.g. `localhost:50051` or `[::1]:50051`) and triggers introspection:

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Nav as NavigationPane (UI)
    participant ConnSvc as ConnectionService
    participant RefSvc as ReflectionService
    participant RefAdapt as GrpcServerReflectionAdapter
    participant Server as Remote gRPC Server
    participant FormSvc as FormSchemaService

    User->>Nav: Enter endpoint & press Enter / Introspect
    Nav->>ConnSvc: connect(target)
    ConnSvc-->>Nav: ManagedChannel
    Nav->>RefSvc: introspect(target, channel)
    RefSvc->>RefAdapt: discoverServices(target, channel)
    RefAdapt->>Server: v1 ServerReflectionRequest(list_services)
    alt Server returns UNIMPLEMENTED on v1
        RefAdapt->>Server: Fallback to v1alpha ServerReflectionRequest(list_services)
    end
    Server-->>RefAdapt: ServerReflectionResponse(ListServicesResponse)

    loop For each discovered Service (excluding grpc.reflection.*)
        RefAdapt->>Server: ServerReflectionRequest(file_containing_symbol = serviceName)
        Server-->>RefAdapt: ServerReflectionResponse(FileDescriptorProto bytes)
    end

    loop For any missing transitive import (cycle-guarded)
        RefAdapt->>Server: ServerReflectionRequest(file_by_filename = depName)
        Server-->>RefAdapt: ServerReflectionResponse(FileDescriptorProto bytes)
    end

    RefAdapt->>RefAdapt: Topologically build FileDescriptors + well-known fallbacks
    RefAdapt-->>RefSvc: Future[Either[DomainError, List[ServiceSchema]]]
    RefSvc-->>Nav: Populate Service/Method TreeView

    User->>Nav: Select RPC Method (e.g. "GetUser")
    Nav->>FormSvc: generateDefaultJson(method)
    FormSvc->>FormSvc: Print defaults (cycle-guarded, oneof-exclusive, snake_case)
    FormSvc-->>Nav: Default JSON payload
    Nav->>User: Render DynamicFormView & JsonEditorPane with defaults
```

---

## 3. Dynamic RPC Call Execution Sequence (Unary & Streaming)

`DynamicCallAdapter` parses JSON into `DynamicMessage` (with transitive `TypeRegistry` for `google.protobuf.Any`), attaches `MetadataAuthInterceptor`, and captures response headers and trailers via a `ForwardingClientCallListener`:

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant ReqPane as RequestPane (UI)
    participant InvokeSvc as InvocationService
    participant CallAdapt as DynamicCallAdapter
    participant Interceptor as MetadataAuthInterceptor
    participant Server as Remote gRPC Server
    participant RespPane as ResponsePane (UI)

    User->>ReqPane: Edit form/JSON & Click "Send Request"
    ReqPane->>InvokeSvc: invokeUnary / invokeServerStreaming / invokeClientStreaming / invokeBidiStreaming
    InvokeSvc->>CallAdapt: execute*(channel, target, method, requestJson, headers)
    CallAdapt->>CallAdapt: Parse JSON -> DynamicMessage + wrap channel with MetadataAuthInterceptor & header/trailer listener
    CallAdapt->>Interceptor: Attach trimmed/lowercased ASCII & -bin headers

    alt Unary Call
        CallAdapt->>Server: ClientCalls.asyncUnaryCall(requestMsg)
        Server-->>CallAdapt: onHeaders, onNext(responseMsg), onClose(status, trailers)
        CallAdapt-->>InvokeSvc: Future[Either[DomainError, RpcCall]]
        InvokeSvc-->>RespPane: Render Status 0 (OK), Latency, Headers/Trailers, JSON Body
    else Server Streaming Call
        CallAdapt->>Server: ClientCalls.asyncServerStreamingCall(requestMsg)
        loop Stream Messages
            Server-->>CallAdapt: onNext(chunk)
            CallAdapt-->>RespPane: onChunk(StreamChunk) -> Stream Log Table
        end
        Server-->>CallAdapt: onCompleted() / onError(t)
        CallAdapt-->>RespPane: onComplete(RpcCallStatus)
    else Client Streaming Call
        CallAdapt->>Server: ClientCalls.asyncClientStreamingCall()
        loop For each chunk sent by User ("Send Chunk")
            ReqPane->>CallAdapt: activeStream.sendNext(requestJson)
            CallAdapt->>Server: requestObserver.onNext(dynamicMsg)
        end
        ReqPane->>CallAdapt: activeStream.complete()
        CallAdapt->>Server: requestObserver.onCompleted()
        Server-->>CallAdapt: onNext(responseMsg) + onCompleted()
        CallAdapt-->>RespPane: onComplete(Right(RpcCall))
    else Bidirectional Streaming Call
        CallAdapt->>Server: ClientCalls.asyncBidiStreamingCall()
        par Client to Server
            loop Concurrent chunks from User
                ReqPane->>CallAdapt: activeStream.sendNext(requestJson)
                CallAdapt->>Server: requestObserver.onNext(dynamicMsg)
            end
        and Server to Client
            loop Real-time incoming chunks
                Server-->>CallAdapt: onNext(chunk)
                CallAdapt-->>RespPane: onChunk(StreamChunk) -> Stream Log Table
            end
        end
    end
```

---

## 4. Dynamic Form & JSON Bi-Directional Synchronization Pipeline

Whether an engineer edits visual widgets or raw JSON, default values and proto field names are preserved:

```mermaid
flowchart LR
    subgraph Input_Sources [Input Sources]
        FormView[DynamicFormView Widgets]
        JsonView[JsonEditorPane / JsonCodeArea]
    end

    subgraph Schema_Engine [Schema & Message Engine]
        ProtoDesc["Pure Schema AST (ProtoType.MessageType / FieldSchema)"]
        RegDesc["DescriptorRegistry (SchemaHandle -> DynamicMessage)"]
        DefaultResolver["FormSchemaService (Defaults + Cycle/Oneof Guard)"]
    end

    subgraph Output_Sync [Synchronized Output]
        DynMsg[DynamicMessage Payload]
        FullJson[Full JSON with Default Values Preserved]
    end

    ProtoDesc --> DefaultResolver
    DefaultResolver --> FormView
    FormView -->|User Edits / Values Collected| FullJson
    JsonView -->|Parse & Hydrate| FormView
    FullJson -->|DescriptorRegistry + JsonFormat.parser| RegDesc
    RegDesc --> DynMsg
    FullJson -.->|Live Sync Updates| JsonView
```

---

## 5. Service Template & Field Memory Lifecycle Sequence

ProtoForm persists per-method payload memory and reusable request templates atomically under `~/.protoform/workspace.json`:

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant ReqPane as RequestPane (UI)
    participant NavPane as NavigationPane (UI)
    participant WorkSvc as WorkspaceService
    participant Repo as LocalJsonWorkspaceRepository
    participant File as ~/.protoform/workspace.json

    Note over User, ReqPane: 1. Auto-saving method payload memory
    User->>ReqPane: Edit field values or JSON
    User->>NavPane: Select different method
    ReqPane->>WorkSvc: saveMethodPayload(prevMethod, currentJson)
    WorkSvc->>Repo: saveWorkspace(updatedData)
    Repo->>File: Atomic write (.tmp -> ATOMIC_MOVE)

    Note over User, ReqPane: 2. Creating & Loading Templates
    User->>ReqPane: Click "Save Template"
    ReqPane->>User: Display Template Dialog (Name, Desc)
    User->>ReqPane: Submit template
    ReqPane->>WorkSvc: saveTemplate(ServiceTemplate(...))
    WorkSvc->>Repo: saveWorkspace(updatedData)
    Repo->>File: Atomic write (.tmp -> ATOMIC_MOVE)
    WorkSvc-->>NavPane: Refresh Templates Tab

    User->>NavPane: Click "Load" on template
    NavPane->>ReqPane: applyTemplate(template)
    ReqPane->>ReqPane: Populate JSON, Auth Preset & Headers
    ReqPane->>User: Form & JSON ready for execution
```
