# Rust example

A minimal Rust client for `PushService.StreamPush`, using [`tonic`](https://github.com/hyperium/tonic) and `tokio`.

## Layout

```
rust/
  Cargo.toml
  build.rs        # invokes tonic-build on ../../push.proto
  src/main.rs
```

`build.rs` runs the proto compiler at build time, so there's no separate codegen step — `cargo run` does it for you.

## Prerequisites

- A recent Rust toolchain (1.75+).
- `protoc` available on `PATH` (`tonic-build` shells out to it). On macOS: `brew install protobuf`.

## Run

```bash
export PUSH_API_KEY=...
export PUSH_API_SECRET=...
export PUSH_APP_ID=...

cargo run
```

## Notes on the generated code

`tonic-build` follows the proto `package` declaration. Since `push.proto` declares `package push;`, the generated code is reachable as `push::*` via:

```rust
pub mod push {
    tonic::include_proto!("push");
}
```

Optional scalar fields (proto3 `optional`) become `Option<T>`. Optional message fields are always `Option<T>`. The two `oneof`s — `PushStreamMessage.payload` and `PushStreamResponse.response` — become Rust enums in submodules: `push::push_stream_message::Payload` and `push::push_stream_response::Response`.
