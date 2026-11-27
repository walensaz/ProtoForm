<div align="center">
  <img src="packaging/icons/protoform.png" alt="ProtoForm Icon" width="84" />
  <h1>ProtoForm</h1>
  <p><strong>A fast, zero-setup desktop gRPC client with default-visible dynamic forms, bi-directional Form ↔ JSON sync, full streaming replay, and offline-first workspaces.</strong></p>
  <p>
    <a href="https://github.com/walensaz/ProtoForm/releases"><img src="https://img.shields.io/github/v/release/walensaz/ProtoForm?style=flat-square&color=0f766e" alt="Latest Release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square" alt="GPL-3.0 License" /></a>
    <img src="https://img.shields.io/badge/platforms-Windows%20%7C%20macOS%20%7C%20Linux-475569?style=flat-square" alt="Platforms" />
    <img src="https://img.shields.io/badge/built%20with-Scala%203%20%2B%20JavaFX%2021-334155?style=flat-square" alt="Scala 3 + JavaFX 21" />
  </p>
</div>

<!--
Uncomment after placing screenshots in docs/screenshots/:
<p align="center">
  <img src="docs/screenshots/protoform-carbon.png" alt="ProtoForm Carbon Dark Theme" width="49%" />
  <img src="docs/screenshots/protoform-paper.png" alt="ProtoForm Paper Light Theme" width="49%" />
</p>
-->

---

## Why ProtoForm?

Most gRPC tools force you into one of two compromises: hunt down `.proto` files and hand-edit raw JSON where Protobuf v3 hides default/zero fields, or sign into heavy cloud-synced suites.

**ProtoForm** treats your gRPC server's schema as an interactive, strongly-typed form right from launch:

* **Zero-Setup Introspection**: Point at an endpoint and hit Introspect (`↻`). Server Reflection (`v1` with automatic `v1alpha` fallback) builds your entire service tree without `.proto` files—or load `.desc`/`.binpb`, raw `.proto` files (`protoc`), or tunnel straight into Kubernetes via `kubectl port-forward`.
* **No Hidden Protobuf Defaults**: Every scalar, `enum`, `oneof`, `repeated`, `map`, `Timestamp`, `Duration`, `FieldMask`, and `bytes` field renders immediately with its default value and stays synchronized bi-directionally with the JSON editor.
* **Built for Real Workflows & QA**: Switch between `dev`/`staging`/`prod` environments with `{{var}}` interpolation, share reusable Auth Profiles (`API Key`, `Bearer`, `Basic`, `Google Cloud`, `Signed JWT`), diff responses against pinned baselines, replay client/bidi JSONL stream scripts, and run automated QA Suites.
* **100% Local, Zero Lock-In**: No accounts, no telemetry, no admin rights required. All workspaces, templates, and history persist atomically as readable JSON under `~/.protoform/`.

---

## Key Features & Use Cases

| Use Case | How ProtoForm Handles It |
|---|---|
| **Explore an unfamiliar microservice** | Server Reflection (`v1` + `v1alpha`), `grpc.health.v1.Health` probe, `Ctrl+K` Quick Jump across methods, and default-populated dynamic forms. |
| **Debug services running in Kubernetes** | Built-in `kubectl` context/namespace/service/pod selector & port-forward tunnel directly in the target bar. |
| **Test complex payloads & `oneof`s** | Bi-directional Form ↔ JSON editing, explicit `proto3 optional` checkboxes, `Timestamp` (`Now`), `Duration` presets, `FieldMask`, and binary `bytes` file picker. |
| **Test streaming RPCs** | Unary, Server Streaming, Client Streaming, and Bidirectional Streaming with `↑ SENT` / `↓ RECV` timeline and JSONL/JSON-array Stream Script Replay. |
| **Authenticate with cloud or internal APIs** | Reusable project/global Auth Profiles: `API Key`, `Bearer Token`, `Basic Auth`, `Google Cloud` (ADC / Service Account), `Signed JWT` (`HS256`/`RS256`), plus custom CA & mTLS. |
| **Run smoke / regression checks** | Save foldered QA Templates with expected gRPC status codes, run batch suites, inspect diffs against pinned baselines, and export Markdown/JSON reports. |
| **Hand off calls to scripts or teammates** | Export any call (with TLS/mTLS, headers, and streaming) to runnable **Python (`grpcio` stubs or `grpcio-reflection`)** or **`grpcurl`**, and import `grpcurl` commands back in 1 click. |
| **Customize your workspace** | 7 runtime themes (led by **ProtoForm Carbon** and **ProtoForm Paper**), multi-tab workbench, drag-and-drop panes, and pop-out `ResponsePane`. |

---

## How ProtoForm Compares

| Capability | ProtoForm | BloomRPC *(archived)* | `grpcui` / `grpcurl` | Cloud API Suites |
|---|---|---|---|---|
| **Offline, local-first (no account/cloud)** | Yes (`~/.protoform/`) | Yes | Yes | Often account/cloud-gated |
| **Server Reflection (`v1` + `v1alpha`)** | Yes | Legacy / partial | Yes | Varies |
| **Offline `.desc` / `.binpb` & `.proto` (`protoc`)** | Yes | `.proto` only | Yes | Varies |
| **Default-visible dynamic forms + JSON sync** | Yes | JSON only | Basic web form / CLI | JSON / partial |
| **Built-in `kubectl port-forward` discovery** | Yes | No | No | No |
| **Client/Bidi JSONL stream script replay** | Yes | Manual | Manual / CLI | Limited |
| **Python (`grpcio`) & `grpcurl` round-trip** | Yes | No | `grpcurl` is CLI | Snippet export varies |
| **Open-source license** | GPL-3.0 | MIT *(unmaintained)* | MIT | Mostly proprietary / freemium |

---

## Install (No Java Required)

Using `curl` or PowerShell downloads the release `.zip` directly to your user directory without browser quarantine prompts or administrator privileges (requires the GitHub repository to be public with a published release, or `GITHUB_TOKEN` set).

### macOS & Linux (`curl`)

```bash
curl -fsSL https://raw.githubusercontent.com/walensaz/ProtoForm/master/packaging/install.sh | sh
```

- **macOS**: Downloads the portable `.zip`, extracts `ProtoForm.app` with `ditto` to `/Applications` (or `~/Applications`), clears any quarantine attribute, and links `~/.local/bin/protoform`.
- **Linux**: Downloads the portable `.zip`, extracts the self-contained runtime to `~/.local/share/protoform`, and symlinks `~/.local/bin/protoform`.
- Pin a specific version with `PROTOFORM_VERSION=1.0.0 curl -fsSL ... | sh`.

### Windows (PowerShell / `curl.exe`)

**PowerShell (`irm`):**

```powershell
irm https://raw.githubusercontent.com/walensaz/ProtoForm/master/packaging/install.ps1 | iex
```

**Or using `curl.exe` + PowerShell:**

```powershell
curl.exe -fsSL https://raw.githubusercontent.com/walensaz/ProtoForm/master/packaging/install.ps1 -o "$env:TEMP\protoform-install.ps1" && powershell -ExecutionPolicy Bypass -File "$env:TEMP\protoform-install.ps1"
```

- Extracts the self-contained reduced-JRE bundle to `%LOCALAPPDATA%\ProtoForm`, verifies the SHA-256 checksum, adds a Start Menu shortcut (`ProtoForm`), and registers `protoform` in your user `PATH`.

### Manual Downloads & Universal Fat JAR

Pre-built native installers (`.msi`, `.dmg`, `.deb`), portable archives, and the universal fat JAR are available on the [GitHub Releases](https://github.com/walensaz/ProtoForm/releases) page. If you already have Java 21 LTS installed:

```bash
java -jar protoform-1.0.0.jar
```

---

## Building & Packaging from Source

### Prerequisites
- **JDK 21 LTS** (with `jpackage` for native installers)
- **sbt** 1.9+
- **Python 3** (optional, only to regenerate icon assets via `packaging/generate_icons.py`)
- **WiX Toolset** (optional on Windows, only for `.msi`/`.exe` installers; `app-image` portable zips work with JDK 21 alone)

### Run Tests, Build Fat JAR & Demo Server

```bash
sbt test                                          # Run ScalaTest suite
sbt assembly                                      # Build target/scala-3.3.3/protoform-1.0.0.jar
java -jar target/scala-3.3.3/protoform-1.0.0.jar    # Launch ProtoForm
sbt "demoServer/run"                              # Start local gRPC sandbox server on 127.0.0.1:50051
```

### Build Native `jpackage` Bundle (Reduced JRE)

**Windows (portable `.exe` app-image + `.zip`, or `.msi`/`.exe` with WiX):**

```powershell
.\packaging\package-windows.ps1 -InstallerType app-image
# Or with WiX Toolset installed:
.\packaging\package-windows.ps1 -InstallerType all
```

**macOS (`.dmg` + `.zip`) / Linux (`.deb` + `.zip`):**

```bash
./packaging/package-unix.sh 1.0.0
```

---

## License, Commercial Exceptions & Support

ProtoForm is free and open-source software licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see [LICENSE](LICENSE).

- **Open Source Guarantee**: Anyone is free to use, study, modify, and redistribute ProtoForm, provided any distributed modifications or derivative works remain open source under GPL-3.0.
- **Private / Commercial Licensing**: If you wish to embed, rebrand, or distribute ProtoForm in a closed-source or proprietary product without GPL-3.0 source-disclosure obligations, open a [GitHub issue](https://github.com/walensaz/ProtoForm/issues) or reach out via [GitHub](https://github.com/walensaz) to arrange a private commercial license.
- **Donations**: ProtoForm is built to stay free for the community. If it saves you time, donations and sponsorships are always welcome!
