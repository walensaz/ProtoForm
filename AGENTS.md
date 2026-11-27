# AGENTS.md — ProtoForm Workspace Guide

> **Canonical Documentation Index** (read on demand; keep this file lean):
> - [`SYSTEM_MAP.md`](SYSTEM_MAP.md) — Hexagonal architecture, reflection & RPC call sequences, form/JSON sync pipeline.
> - [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) — DDD aggregates, value objects, invariants, and `DomainError` hierarchy.
> - [`SYSTEM_REGISTRY.md`](SYSTEM_REGISTRY.md) — Full file inventory, port-adapter mappings, and test suite index.
> - [`COMPONENTS.md`](COMPONENTS.md) — UI architecture, `-pf-*` tokens, themes, modular docking vision, and UI backlog.
> - [`METHODOLOGY_AND_PHILOSOPHY.md`](METHODOLOGY_AND_PHILOSOPHY.md) — SOLID/DRY, agent-first clean code standards, reflection mechanics, and backend backlog.

---

## 1. Overview & Commands

**ProtoForm** is a cross-platform desktop gRPC GUI client with zero-setup server reflection (`v1` + `v1alpha` fallback), default-visible dynamic forms, bi-directional form/JSON sync, auth profiles, full unary/streaming support, and standalone fat-JAR packaging (`~40 MB`).

- **Stack**: Scala 3.3.3 (`-no-indent`), JavaFX 21 LTS, AtlantaFX 2.0.1, RichTextFX 0.11.2, Ikonli Feather, `grpc-netty-shaded` 1.64, Protobuf 3.25, Gson 2.10, ScalaTest 3.2.
- **Themes & Fonts**: ProtoForm Carbon (default), ProtoForm Paper, Primer, Nord, Cupertino; bundled `IBM Plex Sans` & `JetBrains Mono`.

```powershell
sbt test                                     # Compile (with -no-indent) and run ScalaTest suite
sbt assembly                                 # Build standalone fat JAR (net.protoform.Launcher)
java -jar target/scala-3.3.3/protoform-1.0.0.jar  # Launch ProtoForm
.\packaging\package-windows.ps1              # Build reduced-JRE jpackage bundle & zip
sbt "demoServer/run"                         # Launch local demo gRPC server on 127.0.0.1:50051
sbt "demoServer/test"                        # Run demoServer integration tests
```

---

## 2. Package Map (`src/main/scala/net/protoform/`)

See [`SYSTEM_REGISTRY.md`](SYSTEM_REGISTRY.md) for the complete file and test inventory.

- `Launcher.scala` / `ProtoFormApp.scala` — Fat-JAR entrypoint and JavaFX window coordinator.
- `core/` — Pure domain models (`model`, `schema`, `auth`, `call`, `workspace`, `error`); zero JavaFX/Netty imports.
- `ports/` — Outbound port traits (`auth`, `infra`, `rpc`, `reflection`, `schema`, `storage`).
- `application/` — Use-case services (`ConnectionService`, `ReflectionService`, `SchemaService`, `InvocationService`, `FormSchemaService`, `SuiteRunnerService`, `WorkspaceService`).
- `infrastructure/` — Driven adapters (`NettyGrpcChannelAdapter`, `DynamicCallAdapter`, `GrpcServerReflectionAdapter`, `DescriptorRegistry`, `DescriptorSetSchemaAdapter`, `ProtocProtoSchemaAdapter`, `MetadataAuthInterceptor`, `DynamicAuthResolver`, `KubectlPortForwardAdapter`, `LocalJsonWorkspaceRepository`).
- `ui/` — JavaFX presentation (`state`, `forms`, `editor`, `views`, `util`, `theme`) + `src/main/resources/{fonts,styles}/`.

---

## 3. Engineering & UI Guardrails

### Architecture & Clean Code
1. **Explicit Scala 3 Braces Only (`-no-indent`)**: Always use `{ ... }` for classes, traits, objects, enums, methods, and control blocks. Never use Python-style significant indentation (`:` / `end`).
2. **Agent-First Clean Code**: Keep files small and cohesive (~≤300 LOC); extract shared helpers (DRY); write minimal *why-only* comments (never narrate *what* code does); remove unused imports and dead code.
3. **Hexagonal Boundaries**: `net.protoform.core` must remain 100% pure Scala with zero JavaFX, Protobuf, Netty, or gRPC transport imports. Infrastructure is accessed strictly through `net.protoform.ports`, with runtime Protobuf descriptors resolved via `DescriptorRegistry` and opaque `SchemaHandle`s.
4. **Immutability & Concurrency**: Domain models are immutable case classes; mutable UI state lives only in JavaFX properties. Use atomic/synchronized state for channel pools, active streams, and workspace persistence.
5. **Default-Value Transparency**: Always configure `JsonFormat.printer().includingDefaultValueFields().preservingProtoFieldNames()` unless toggled off by the user.
6. **Non-Blocking Streaming & Reflection**: Never block the FX thread with network I/O; maintain non-blocking `ActiveClientStream` callbacks and bounded timeouts.
7. **Fat JAR & User-Space Sandbox**: Keep `assembly / mainClass := Some("net.protoform.Launcher")` in `build.sbt`. Persist state atomically and exclusively under `~/.protoform/` with zero admin privileges.

### JavaFX, CSS & Modular UI
8. **Valid JavaFX CSS Only**: No web-only CSS (`!important`, `-fx-letter-spacing`, comma-separated font fallbacks). Use `"IBM Plex Sans"` or `"JetBrains Mono"` with `-fx-font-smoothing-type: gray` on `.text`.
9. **Zero Inline `.setStyle(...)`**: Use semantic CSS classes and `-pf-*` tokens so all dark and light themes stay consistent.
10. **Theme-Safe Popups & Dialogs**: Swap `Scene` stylesheets (`tokens.css` → `theme-*.css` → `custom.css`) via `ThemeManager`; call `ThemeManager.styleDialog(dialog)` on every `Dialog`/`Alert`.
11. **Precision-Instrument Contrast**: Grounded desktop surfaces (warm graphite + signal teal / architectural daylight; no hollow wireframe buttons or neon-purple SaaS glow). Solid buttons with white text must keep ≥ 4.5:1 contrast including `:hover`.
12. **Cell & Pane Modularity**: Reuse node graphs in `TreeCell`/`ListCell` constructors; guard synchronous RichTextFX highlighting on huge payloads; keep panes decoupled from the primary `Stage` via `AppState` for future IntelliJ-style docking/pop-out.
13. **Compact 2-Pane Default & Segmented Alignment**: Keep connection/schema controls unified inside `TargetBarControl` within the single `46px` `CustomTitleBar` (locking segment heights to `28px` with zero inset gaps), default to the 2-pane split + `40px` `ActivityRail` with progressive `•••` / Power Mode (`showAdvancedActions`), and debounce-persist window bounds and divider positions on resize.

### Verification & Living Documentation
14. **Always Update Living Documentation**: At the end of every feature, refactor, or architectural change, update the affected canonical docs (`SYSTEM_REGISTRY.md`, `SYSTEM_MAP.md`, `DOMAIN_MODEL.md`, `COMPONENTS.md`, `METHODOLOGY_AND_PHILOSOPHY.md`, and `AGENTS.md`) to match the implemented code and tests before finishing.
