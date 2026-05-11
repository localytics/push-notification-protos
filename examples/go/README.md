# Go example

A minimal Go client for `PushService.StreamPush`.

## Layout

```
go/
  main.go         # the client
  go.mod
  Makefile        # `make gen` / `make build` / `make run`
  gen/push/       # generated stubs (created by `make gen`)
```

## Prerequisites

- Go ≥ 1.22
- `protoc` (the protobuf compiler)
- The Go protoc plugins. The `Makefile` will `go install` them if missing:
  - `google.golang.org/protobuf/cmd/protoc-gen-go`
  - `google.golang.org/grpc/cmd/protoc-gen-go-grpc`

Make sure `$(go env GOPATH)/bin` is on your `PATH` so `protoc` can find the plugins.

## Run

```bash
export PUSH_API_KEY=...
export PUSH_API_SECRET=...
export PUSH_APP_ID=...

make run
```

`make gen` reads `../../push.proto` and writes the stubs into `gen/push/`. `main.go` imports them as `pb "example.com/pushclient/gen/push"`.
