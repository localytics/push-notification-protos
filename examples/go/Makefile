.PHONY: gen build run clean

# Generate Go + gRPC stubs from ../../push.proto into ./gen/push.
# Requires `protoc`, `protoc-gen-go`, and `protoc-gen-go-grpc` on PATH.
gen:
	@command -v protoc >/dev/null || { echo "protoc not found"; exit 1; }
	@command -v protoc-gen-go >/dev/null || go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
	@command -v protoc-gen-go-grpc >/dev/null || go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
	mkdir -p gen/push
	protoc \
	  -I=../.. \
	  --go_out=./gen/push --go_opt=paths=source_relative \
	  --go-grpc_out=./gen/push --go-grpc_opt=paths=source_relative \
	  ../../push.proto
	go mod tidy

build: gen
	go build -o push-client .

run: build
	./push-client

clean:
	rm -rf gen push-client
