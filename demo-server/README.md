# ProtoForm Demo gRPC Server

A self-contained gRPC sandbox subproject (`demoServer`) built to test every feature of **ProtoForm**: zero-setup server reflection (`v1` & `v1alpha`), health checks, dynamic forms, well-known types, `oneof`s, `map`s, `bytes`, auth headers, response headers/trailers, gRPC errors, and all 4 streaming patterns.

---

## Quick Start

To run **ProtoForm** and the **Demo Server** at the same time without `sbt` server/lock conflicts, run them as standalone JARs (which is what both launcher scripts do):

1. Start the demo server (builds `protoform-demo-server-1.0.0.jar`, exits `sbt`, and runs on `java -jar`):
   ```powershell
   demo-server\run-demo.bat
   ```
   (Pass `--no-build` as the first argument to skip `sbt "demoServer/assembly"` if the JAR is already built.)

2. In a second terminal (or via `run.bat`), start **ProtoForm**:
   ```powershell
   .\run.bat
   ```

> **Tip:** If you prefer running directly from `sbt` during active coding, you can also run `sbt "demoServer/assembly"` once and launch `java -jar demo-server\target\scala-3.3.7\protoform-demo-server-1.0.0.jar` in the background while keeping your `sbt` shell free for ProtoForm (`sbt run` / `sbt test`).

Then in **ProtoForm**:
1. Enter `127.0.0.1:50051` in the target bar (plaintext / TLS off).
2. Click **Introspect** (`↻`) — ProtoForm discovers `demo.sandbox.v1.SandboxService` and `demo.orders.v1.OrderService` automatically via server reflection.
3. Click `Refl ▾` → **Check Health** (`grpc.health.v1.Health`) to verify `SERVING`.

### Offline `.desc` Schema Testing

A matching compiled `FileDescriptorSet` is included at `demo-server/schemas/sandbox.desc` (and can be regenerated via `sbt "demoServer/run --write-desc demo-server/schemas/sandbox.desc"`).
Use `Refl ▾` → **Load .desc / .binpb…** in ProtoForm to test offline schema loading (or run the server with `--no-reflection`).

Human-readable `.proto` sources live in `demo-server/protos/demo/`.

---

## Available Services & Test Scenarios

### 1. `demo.sandbox.v1.SandboxService`

| Method | Pattern | What to Test |
|---|---|---|
| `Echo` | Unary | Scalars, `bytes` file upload, `Priority` enum, nested `ClientInfo`, `repeated tags`, `map<string, string>`, `Timestamp`/`Duration`/`FieldMask`, `oneof detail` (`note` / `code` / `Struct extra`), incoming request metadata echo, and response headers/trailers (`x-demo-server`, `x-request-id`, `x-demo-trace`). |
| `SimulateBehavior` | Unary | Error banner, status codes (`INVALID_ARGUMENT`, `NOT_FOUND`, `PERMISSION_DENIED`, `UNAUTHENTICATED`, `UNAVAILABLE`, `INTERNAL`), artificial `delay_ms` (for deadline / timeout tests), QA Suite assertions, and Response Diff. |
| `StreamEvents` | Server Streaming | Streaming log viewer, cancellation, configurable `count` (1–50) and `interval_ms`. |
| `CollectMetrics` | Client Streaming | Manual multi-send + `StreamScriptPane` JSONL/JSON array replay (`metric_name`, `value`, `labels`). |
| `InteractiveChat` | Bidi Streaming | Real-time send/receive; special commands: `/ping`, `/count`, `/time`, `/fail` (mid-stream `ABORTED` error). |

### 2. `demo.orders.v1.OrderService`

| Method | Pattern | What to Test |
|---|---|---|
| `GetOrder` | Unary | Pre-seeded orders: `ord-1001`, `ord-1002` (any other ID returns `NOT_FOUND`). |
| `CreateOrder` | Unary | Validation (`INVALID_ARGUMENT` on empty `customer_id`/`items`), repeated nested `OrderItem` + `Money`, in-memory state. |
| `ListOrders` | Unary | Filter by `customer_id` or `status_filter`. |
| `GetAdminAudit` | Unary (Auth) | Requires **Bearer** `demo-secret-token` OR **`x-api-key`** `demo-api-key`; otherwise returns `UNAUTHENTICATED`. |
| `WatchOrderUpdates` | Server Streaming | Streams `PENDING` → `CONFIRMED` → `PACKED` → `SHIPPED` → `DELIVERED` for an existing order ID (`ord-1001`). |

---

## CLI Options

```powershell
sbt "demoServer/run --host 127.0.0.1 --port 50051"
sbt "demoServer/run --no-reflection"
sbt "demoServer/run --cert path\to\server.crt --key path\to\server.key"
sbt "demoServer/run --write-desc demo-server\schemas\sandbox.desc"
```
