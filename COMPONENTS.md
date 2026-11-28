# ProtoForm — UI, Theming & Modular Workspace Guidelines

This document is the canonical reference for visual styling, theming, dynamic form controls, and the modular IntelliJ-style workspace architecture in **ProtoForm**.

---

## 1. UI Stack & Design Philosophy

- **Toolkit**: JavaFX 21 LTS (hardware-accelerated Prism pipeline)
- **Theme Engine**: **AtlantaFX 2.0.1** (`PrimerDark`, `PrimerLight`, `NordDark`, `CupertinoDark`, `CupertinoLight`) extended by ProtoForm’s `-pf-*` semantic token layer
- **Code Editor**: **RichTextFX 0.11.2** (`JsonCodeArea` with virtualized line-number gutters & JSON syntax highlighting)
- **Iconography**: Kordamp Ikonli **Feather** vector icon pack (`ikonli-feather-pack`)
- **Bundled Typography**: Static **IBM Plex Sans** (`Regular`, `Medium`, `SemiBold`, `Bold`) + **JetBrains Mono** (`Regular`, `Bold`) loaded via `Font.loadFont` at startup

### Design Identity: Precision Desktop Instrumentation
ProtoForm intentionally avoids generic purple-glow SaaS aesthetics and outdated JavaFX Modena chrome. Instead, it follows a **precision developer tool** visual language:
1. **Compact 2-Pane Default with Tactile Depth**: Starts in a focused 50/50 Request | Response split with a slim `40px` left Activity Rail (`Services`, `Templates`, `History`), `31px` inputs, crisp `1px` hairlines, and 3-tier surface elevation (Canvas → Pane → Card).
2. **Tactile Solid Controls**: Solid primary/secondary/success/danger buttons with ≥ 4.5:1 text contrast in all states, proportionate compact padding (`5px 11px`), theme-matched vector icons, focus rings, and explicit `-fx-cursor: hand;`.
3. **Zero Hidden Defaults**: Every declared protobuf field is visibly rendered with its default value and quiet metadata tag (`type · #N`, plus `edited` badge when modified or full pills in Power Mode).
4. **Zero Inline Styles**: All colors, surfaces, and states flow through `-pf-*` CSS tokens so dark and light themes switch instantaneously with zero contrast regressions.

---

## 2. Theme System (`ThemeManager.scala`)

ProtoForm ships with **7 runtime-switchable themes**, led by two signature themes:

| Theme | Base | Visual Character |
|---|---|---|
| **ProtoForm Carbon** *(Default)* | `PrimerDark` + `theme-carbon.css` | Warm architectural graphite (`#101216` → `#171b22` → `#1f2530`) with deep signal-teal accent (`#0f766e` / `#2dd4bf`). |
| **ProtoForm Paper** | `PrimerLight` + `theme-light.css` + `theme-paper.css` | Crisp daylight technical-manual aesthetic (`#edf0f4` canvas, `#f6f7f9` panes, `#ffffff` cards/inputs, deep teal ink). |
| **Primer Dark / Light** | AtlantaFX Primer | GitHub-slate dark and high-contrast light modes. |
| **Nord Dark** | AtlantaFX Nord | Arctic muted slate-blue dark palette. |
| **Cupertino Dark / Light** | AtlantaFX Cupertino | macOS-inspired dark and light surfaces. |

### Swapped Stylesheet Architecture (Popup-Safe & Custom Dialogs)
Rather than toggling theme classes only on `Scene.getRoot` (which misses JavaFX `PopupWindow`s like `Tooltip`, `ContextMenu`, and `ComboBox` dropdowns), `ThemeManager` swaps the ordered stylesheet stack directly on the primary `Scene` and on every secondary/floating `Stage` (`registerSecondaryScene`):
1. `/styles/tokens.css` — Base dark `-pf-*` tokens & typography
2. Optional theme overlay (`/styles/theme-carbon.css`, `/styles/theme-light.css`, `/styles/theme-paper.css`)
3. `/styles/custom.css` — All component rules

All modals, alerts, confirmations (`showConfirm`), prompts, and floating pop-out windows are built through `CustomDialogStage` (`StageStyle.UNDECORATED` + custom `42px` header bar + `.app-root` border + `WindowResizeHelper`) so every window shares identical ProtoForm chrome and active theme tokens.

---

## 3. Window Layout & Core Panes

ProtoForm launches at `1480 × 920` (clamped to screen visual bounds, min `960 × 620`) with a frameless custom titlebar, dock/taskbar-aware maximize (`WindowStateController` using `Screen.getVisualBounds`), collapsible/rearrangeable split panes, and telemetry status bar:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  [>_ ProtoForm ▾]  [🔒 TLS | localhost:50051 ▾ | Refl ▾ | ↻]  [Env: Dev ▾]    [⌘ Jump] ◫ ⚙ ─□✕│
├──┬──────────────────────────────────────────┬───────────────────────────────────────────────┤
│📚│  [OrderService ▾] [GetOrder ▾] [U] [•••]▶│ [gRPC: 0 OK] 14ms · 1.2KB       [Copy][↗][•••]│
│🔖│  Req: GetOrderRequest -> Resp: GetOrder  │ ───────────────────────────────────────────── │
│🕒│  [Form View] [JSON View] [Headers & Auth]│ [Response Body] [Headers & Trailers] [Stream] │
│  │                                          │ 1 | {                                         │
│  │  order_id                   string · #1  │ 2 |   "id": "99",                             │
│  │  [ "ord_10294"                         ] │ 3 |   "paid": true                            │
│  │                                          │ 4 | }                                         │
├──┴──────────────────────────────────────────┴───────────────────────────────────────────────┤
│  ● Connected (localhost:50051) · 2 services  |  Ready   Ctrl+Enter · Ctrl+K · Ctrl+B · Ctrl+L│
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Dynamic Form Component Catalog (`FormControls.scala` & `DynamicFormView.scala`)

| Proto Type | Control Component | Default Value | Behavior |
|---|---|---|---|
| `string` | `ScalarStringControl` (`TextField`) | `""` | Standard text input with default placeholder hint. |
| `bytes` | `BytesControl` (`TextField` + `File…` + byte pill) | `""` | Base64 input with binary file loader and live decoded byte-count/validation pill. |
| `google.protobuf.Timestamp` | `TimestampControl` (`TextField` + `Now` + ISO pill) | `"1970-01-01T00:00:00Z"` | RFC3339 / ISO-8601 UTC editor with 1-click `Now` and live validation. |
| `google.protobuf.Duration` | `DurationControl` (`TextField` + presets + norm pill) | `"0s"` | Human duration editor (`100ms`, `1s`, `5s`, `30s`) normalized to canonical proto `"Ns"`. |
| `google.protobuf.FieldMask` | `FieldMaskControl` (`TextField` + path count) | `""` | Comma-separated field path editor with path-count pill. |
| `int32` / `int64` / `uint*` / `sint*` / `fixed*` | `ScalarNumericControl` (`TextField`) | `0` | Numeric input validation. |
| `float` / `double` | `ScalarNumericControl` (`TextField`) | `0.0` | Floating-point input validation. |
| `bool` | `ScalarBoolControl` (`ToggleSwitch` + mono label) | `false` | Tactile switch showing `true` / `false (default)`. |
| `enum` | `EnumControl` (`ComboBox[String]`) | Index `0` | Populated from `ProtoType.EnumType` values. |
| `repeated` | `RepeatedFieldControl` | `[]` | Dynamic item rows with `+ Add Item` and per-row delete. |
| `map<K, V>` | `MapFieldControl` | `{}` | Key-value rows with `+ Add Entry` and per-row delete. |
| `message` | `NestedMessageControl` (`TitledPane`) | Instantiated | Collapsible `.nested-message-pane` with left accent rail. |

---

## 5. Key UI Components

### 5.1 Custom Title Bar (`CustomTitleBar.scala`), `TargetBarControl.scala`, `WorkbenchTabBar.scala` & Resizing
- `StageStyle.UNDECORATED` with a unified `46px` header bar: bordered vector Proto-Owl mark + `ProtoForm` quick menu (`Show Defaults`, `Show Advanced Toolbar Actions`, `Theme` submenu, `Layout & Modular Panes`), labeled `[Workspace: <name> ▾]` menu button (1-click switch, `+ New Workspace…`, and sidebar shortcut), embedded `TargetBarControl`, Environment Switcher (with `Manage Environments…`), `Jump`, `Response Pane` quick toggle, `Settings`, and min/max/close controls.
- `TargetBarControl` (`30px` pill with uniform `28px` segments) unites the `TLS` / `Plain` lock toggle (`Feather.LOCK` / `Feather.UNLOCK`), editable recent-endpoints `ComboBox` (`Ctrl+L`), clickable Schema source chip (`Refl`, `.desc`/`.binpb`, `.proto`, `Kubernetes Port-Forward…`, Health Check), and icon-only `↻` Introspect button.
- `WorkbenchTabBar` (`34px` strip above the Request/Response split) enables multi-tab sessions (`Ctrl+T`, `Ctrl+W`, `Ctrl+Tab`, `Ctrl+Click` / right-click `Open in New Tab`) with per-tab request/response/auth/stream isolation and background streaming indicators.
- `WindowResizeHelper` provides 8-direction resizing (`8px` side/bottom edges, `5px` top edge clear of titlebar buttons, `14px` corners, plus a subtle bottom-right status-bar resize grip on Windows/Linux), macOS menu-bar `minY` clamping, maximized-edge detachment, and shared `4px`-threshold header dragging with double-click maximize anywhere outside interactive controls. On macOS, stages use native `StageStyle.DECORATED` framing (with native traffic lights, native 8-way Retina resize, and native double-click Zoom/tiling) while `CustomTitleBar` acts as a unified `46px` toolbar and scales cleanly down to `820 × 560` without clipping window controls. `WindowStateController` and `SplitPane` divider listeners debounce-save window bounds and pane ratios (`400ms`) on resize/move.

### 5.2 Activity Rail (`ActivityRail.scala`) & Drawer (`NavigationPane.scala`)
- **Slim `40px` Activity Rail**: Houses `Services` (with live green connection dot), `Templates` (with count badge), `History`, `Workspaces` (`Feather.BRIEFCASE`), and a bottom `Suite` runner shortcut. Defaults to collapsed for a 2-pane workspace; `Ctrl+B` or `Ctrl+1/2/3/4` toggles/switches the docked drawer.
- **StackPane Drawer**: Compact section header (`SERVICES`, `TEMPLATES`, `HISTORY`, `WORKSPACES` + contextual `Suite` / `Clear` / `New` / `Import` / `Collapse` controls) over a `StackPane` switching between the reusable-cell `Services` tree, context-smart foldered `Templates` tree (`Method | Service | All`), `History` list, and `Workspaces` list (live counts, inline `Switch / Rename / Export / Delete`, double-click switch).

### 5.3 Request Builder (`RequestPane.scala`, `SearchableSelector.scala`) & JSON Editor (`JsonCodeArea.scala`)
- **Symmetric Single-Row Header & Unified Method Pill**: Both `RequestPane` and `ResponsePane` share `.pane-header-bar` (`48px` min-height) so `TabPane` baselines align horizontally across the split. `RequestPane` groups searchable `[Service | ▾]` and `[Method | ▾]` selectors inside `.method-pill-box` alongside the centered `•••` overflow menu (`29×29px` uniform `.icon-only-btn`) and dedicated green `.btn-run` (`-pf-run`) `▶` / `■` icon button (`.play-icon-btn`); pattern pills appear only for streaming RPCs, and streaming controls live in a framed `.streaming-control-bar` below.
- **Connected Tabbed Panel Layering**: Across both `RequestPane` and `ResponsePane`, `.tab:selected` uses a `2px` top `-pf-accent` border and fuses seamlessly into a bordered, `10px`-padded `.tab-content-area` (`-pf-bg-pane`), clearly anchoring `Form View`, `JSON View`, `Headers & Auth`, `Response Body`, `Headers & Trailers`, and `Stream Log` within distinct surface boundaries.
- **Actionable Cold Start**: When disconnected or no method is selected, `Form View` renders direct `Introspect Target`, `Load .desc / .proto`, `Import grpcurl…`, or `Quick Jump (Ctrl+K)` / `Services Rail (Ctrl+B)` actions.
- **Form View**: Scrollable `DynamicFormView` renders all fields of a message inside a unified `.message-object-card` (with a `.message-object-header` strip showing `Request → Response` and field count, `1px`-divided `.message-field-row` items, `type · #N` mono metadata, explicit enable `CheckBox`es for `proto3 optional` fields, `edited` badges when modified or full pills in Power Mode, indexed `.collection-item-row` entries with subtle `.row-delete-btn` icons for `repeated`/`map`, and `.oneof-branch-box` accent framing).
- **JSON View**: `JsonEditorPane` wrapping `JsonCodeArea(editable = true)` with line numbers, live JSON token highlighting (capped at 250 KB for UI smoothness), Format JSON, and Clear.
- **Headers & Auth Tab** (`MetadataEditor.scala`): Reusable Auth Profile selector (project-scoped or `[Global]`), Auth Mode (`None`, `API Key`, `Bearer Token`, `Basic Auth`, `Google Cloud`, `Signed JWT`) with full-width `ColumnConstraints` and live token preview, and custom gRPC metadata rows.

### 5.4 Response Viewer (`ResponsePane.scala`)
- Single-row status pill (`.status-badge-ok` / short `ERR: code` `.status-badge-error`), monospace latency/size pills, and compact centered icon buttons (`Copy`, `Pop Out`, `•••` for `Pin`, `Diff`, and `Clear Stream Log`); enabling `Show Advanced Toolbar Actions` expands those buttons with labels.
- **Error Summary Banner**: Long gRPC failure messages render in a wrapping, copyable, and dismissible `.response-error-banner` above the tabs instead of overflowing the status pill.
- **Response Body**: Read-only `JsonCodeArea` with syntax highlighting, line numbers, and a non-blocking empty-state prompt before the first RPC run.
- **Headers & Trailers / Stream Log**: `TableView`s using `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN`, zebra-striped `:filled` rows, clean borderless `:empty` rows, vertical column dividers, `Kind` (`HEADER`/`TRAILER`) pill column with double-click / context-menu copy in `Headers & Trailers`, direction pills + inline `Clear` / `Auto-Follow` toolbar in `Stream Log`, and a static `DateTimeFormatter`.

---

## 6. Color-Coded Badges & Status Dots
- **RPC Pattern Pills** (`.rpc-pill-*`): `UNARY` (teal), `SERVER` (sky), `CLIENT` (amber), `BIDI` (fuchsia).
- **Protobuf Type Badges** (`.badge-type-*`): `string`/`bytes` (sky), numeric (amber), `bool` (violet), `enum` (teal), `message` (blue), `repeated` (rose), `map` (fuchsia). All adapt to dark and light themes via `-pf-type-*` tokens.
- **Status Indicators**: Styled CSS `.status-dot` circles (idle, connecting, connected, warn, error) and Feather icons—no raw Unicode symbols or emojis.

---

## 7. Modular Docking & Multi-Window Architecture (IntelliJ-Style Vision)

ProtoForm’s UI is evolving toward a **modular, IntelliJ-style dockable workspace** so developers can tailor their layout across single or multi-monitor setups:

- **Self-Contained Dockable Modules**: Every core view (`NavigationPane`, `RequestPane`, `ResponsePane`, `StreamLog`, `MetadataEditor`, `JsonEditorPane`) must remain independent of the primary `Stage` and communicate exclusively via `AppState` / reactive properties and application services.
- **Rearrangeable Tabs**: Users should be able to drag tabs to reorder them within a `TabPane` or move them across split panes (e.g., placing `Headers & Auth` side-by-side with `Form View`, or moving `Stream Log` below `Response Body`).
- **Pop-Out / Floating Windows**: Any tab or tool pane should be detachable into its own floating `Stage` (e.g., popping `ResponsePane` or a live `Bidirectional Stream` monitor onto a second monitor) while automatically inheriting the active `ThemeManager` stylesheets and live `AppState` updates, with a 1-click re-dock action.
- **Workspace Layout Persistence**: Custom split ratios, tab orders, and docked/floating pane states will persist in `~/.protoform/workspace.json` alongside recent endpoints and templates.

---

## 8. Implemented UI Milestones & Remaining Docking Backlog

### Recently Completed
1. **Bi-Directional Form ↔ JSON Sync & Live `Show Defaults`**: `DynamicFormView.populateFromJson` hydrates all controls on tab switch, method restore, template load, and history load (including cross-pane docked tabs); `Show Defaults` and `Preserve Proto Field Names` live-control JSON/wire output and `default`/`edited` badges.
2. **Recursive Proto Guard & `oneof` Switcher**: `DynamicFormView` consumes pure `ProtoType.MessageType`/`FieldSchema`, enforces `MaxRecursionDepth = 8` + visited set, and renders `OneofFieldControl` branch selectors.
3. **Streaming Completion Badge, Master-Detail Stream Timeline & Script Replay**: Server/Bidi `onComplete` updates `appState.activeCall`; `ResponsePane` shows payload byte size, `↑ SENT` / `↓ RECV` badges, `Δ Time`, click-to-inspect chunks, and `Auto-Follow`; `StreamScriptPane` replays JSONL or JSON-array chunk sequences with configurable delay and auto-complete.
4. **Top-Left Quick Settings & Searchable Settings Dialog**: Top-left `>_ ProtoForm` opens Quick Settings & Layout controls; `SettingsDialog` provides left-sidebar category navigation, live search filtering, mTLS cert/key & `protoc` path pickers, context-aware dirty-tracked `Save Settings`, and custom undecorated window chrome (`CustomDialogStage`).
5. **QA Templates, Suite Runner, Diff Viewer, Code Export, `grpcurl` Import & Keyboard Speed**: Service/method filter, `Ctrl+Enter`, `Ctrl+K` Quick Jump, `Copy as grpcurl`, `CodeExportDialog` (`Python grpcio` stubs & `grpcio-reflection` + `grpcurl`), `GrpcurlImportDialog`, `ProtoImportDialog` (`Load .proto`), `SuiteRunnerDialog` (tag-filtered sequential `expectedStatus` verification + report export), and `ResponseDiffDialog`.
6. **Environments, Drag-and-Drop Tabs, Bounds Persistence & Pop-Out `ResponsePane`**: `CustomTitleBar` Environment Switcher + `EnvironmentManagerDialog` (`{{var}}` interpolation), `TabDragSupport` within-pane and `RequestPane` ↔ `ResponsePane` drag-and-drop tab reordering persisted in `AppSettings`, `WindowStateController`, and floating `DockingManager`.
7. **Compact 2-Pane Default, Unified Pills & Visual Symmetry**: Merged connection controls into `TargetBarControl` inside `CustomTitleBar` (with raised `TLS` toggle, explicit dropdown caps, uniform `28px` segment heights, and icon-only `↻`), added the `40px` `ActivityRail` + `StackPane` `NavigationPane` drawer (collapsed by default), unified `[Service | ▾ | Method | ▾]` `.method-pill-box` + green `-pf-run` `▶` Execute button, symmetric `48px` Request/Response headers, `.message-object-card` unified field grouping, `ResponsePane` error summary banner, `Show Advanced Toolbar Actions` Power Mode toggle, and `400ms` debounced persistence on window resize/move and divider drags.

---

## 9. UI Design Lessons & Ergonomics (Living Guidelines)

1. **Single Unified Top Bar Over Stacked Toolbars**: Stacking a separate connection bar beneath `CustomTitleBar` creates an awkward half-empty strip and wastes vertical space. Embedding `TargetBarControl` (`[TLS | host:port | Refl ▾ | ↻]`) directly beside `[>_ ProtoForm ▾]` and `[Env ▾]` keeps chrome compact while still leaving generous `dragSpacer` room at `1000px` min width.
2. **Lock Segmented Pill Heights Explicitly in JavaFX CSS**: AtlantaFX gives `ToggleButton`, `ComboBox`, `MenuButton`, and `Button` different default heights, padding, and `-fx-background-insets`. Every segment inside `.target-pill-box` must explicitly lock `-fx-min-height`/`-fx-pref-height`/`-fx-max-height` (`28px`), `-fx-background-insets: 0`, and flush corner radii so controls align without floating gaps.
3. **Never Float Transport or Schema Indicators**: `TLS` belongs on the endpoint pill as a lock toggle (`🔒 TLS` / `🔓 Plain`) rather than a loose checkbox, and schema origin (`Reflection` / `.desc` / `.proto`) belongs as an integrated clickable chip on the target bar—not unanchored sidebar text.
4. **2-Pane Focus by Default + On-Demand Activity Rail**: Most developer sessions target a single service and a few methods. Starting in a 50/50 Request | Response split with `[Service ▾]`/`[Method ▾]` selectors and a `40px` left `ActivityRail` (`Services`, `Templates`, `History`, `Workspaces`) avoids permanently dedicating ~25% width to a tree while keeping QA and workspace tools 1 click (`Ctrl+B` / `Ctrl+1/2/3/4`) or `Ctrl+K` away, alongside the always-visible top-bar `[Workspace: <name> ▾]` menu.
5. **Tactile Depth Without Badge Bloat**: Keep ProtoForm's elevated cards, crisp `1px` borders, and `.nested-message-pane` accent rails, but group all fields of a message into a single `.message-object-card` with `1px` `.message-field-row` dividers (rather than separate floating cards per scalar) and replace triple-pill headers with `string · #1` + dirty `edited` pills.
6. **Symmetric Panes, Explicit Affordances & Overflow Guards**: Match `RequestPane` and `ResponsePane` header heights (`48px` `.pane-header-bar`) so tab strips align across the split; give interactive toggles/dropdowns explicit tactile button caps (raised `.tls-toggle-btn`, `1px`-divided `.arrow-button` / `.selector-arrow-cap`); zero out `.menu-button.no-caret > .arrow-button` width and center `> .label` (`GRAPHIC_ONLY`, `CENTER`) so `•••` icons sit dead-center inside `29×29px`; lock `.combo-box > .list-cell` vertical padding (`4px 10px`) so enum labels never clip; use dedicated `-pf-run` emerald tokens for `▶` Execute; hide `[UNARY]` noise from headers; wrap long gRPC errors in `.response-error-banner`; fuse `.tab:selected` (with a `2px` top accent) directly into a bordered `.tab-content-area` (`-pf-bg-pane`) so every tab’s content is clearly bounded; and keep a 1-click Response Pane toggle in the top bar.

### Future UI Polish Ideas
1. **Multi-Pane Individual Tab Pop-Out**: Allow detaching individual dragged tabs into standalone floating `CustomDialogStage` windows via `DockingManager`.
2. **Graph / Schema Visualizer**: Interactive service dependency and message tree explorer for large protobuf repositories.
