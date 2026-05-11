# examples/

Minimal, runnable clients for the `PushService.StreamPush` RPC defined in [`../push.proto`](../push.proto).

Each subfolder is a self-contained project that:

1. Generates stubs from `../../push.proto`.
2. Opens a bidirectional stream to the sandbox endpoint with HTTP Basic auth.
3. Sends one `StreamInit` followed by one `PushRequest` for two customer IDs.
4. Drains any `PushFailure` frames and prints the final `PushResponse` summary.

| Folder | Language | Build tool | Toolchain |
|--------|----------|-----------|-----------|
| [`go/`](go) | Go | `go` + `protoc` (via `Makefile`) | Go ≥ 1.22, `protoc`, `protoc-gen-go`, `protoc-gen-go-grpc` |
| [`java/`](java) | Java | Gradle 9.5 (wrapper) + `protobuf-gradle-plugin` | JDK 17 (auto-downloaded via Foojay) |
| [`rust/`](rust) | Rust | `cargo` + `tonic-build` | Rust 1.75+, `protoc` |

All three reference `../../push.proto` directly (the Go and Rust examples via build-time codegen; Java via a `src/main/proto/push.proto` symlink), so the schema has one source of truth.

All three have been verified to build cleanly with `make build` / `./gradlew build` / `cargo build`.

All three read the same three environment variables:

- `PUSH_API_KEY`
- `PUSH_API_SECRET`
- `PUSH_APP_ID`

These are intentionally small — they exist to show the *protocol flow*, not production-grade clients. For Python / Node.js examples and the full integration guide (auth, limits, gRPC status-code table, retry strategy), see [`../usage.md`](../usage.md).
