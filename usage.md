# gRPC Push Service — Client Integration Guide

## Table of Contents

- [Overview](#overview)
- [Service Definition](#service-definition)
- [Protocol Flow](#protocol-flow)
- [Authentication](#authentication)
- [Limits](#limits)
- [Message Reference](#message-reference)
- [Client Examples](#client-examples)
  - [Go](#go)
  - [Python](#python)
  - [Node.js (JavaScript / TypeScript)](#nodejs-javascript--typescript)
  - [Java](#java)
  - [cURL / grpcurl](#curl--grpcurl)
- [Error Handling](#error-handling)
- [Best Practices](#best-practices)

---

## Overview

The Push Service exposes a single **bidirectional streaming** gRPC endpoint that accepts push notification requests and returns delivery results in real time.

| Property | Value |
|----------|-------|
| **Proto package** | `push` |
| **Service** | `PushService` |
| **Method** | `StreamPush` |
| **Streaming** | Bidirectional (client → server, server → client) |
| **Sandbox endpoint** | `trans-api-grpc.sandbox53.localytics.com:50051` |

---

## Service Definition

```protobuf
service PushService {
  rpc StreamPush(stream PushStreamMessage) returns (stream PushStreamResponse);
}
```

The proto file is located at [`push.proto`](push.proto).

---

## Protocol Flow

```
Client                                            Server
  │                                                  │
  │──── StreamInit (app_id, request_id, ...) ───────►│  ← must be first message
  │                                                  │
  │──── PushRequest (customer_ids, alert, ...) ─────►│
  │──── PushRequest ────────────────────────────────►│
  │──── PushRequest ────────────────────────────────►│
  │──── CloseSend() ────────────────────────────────►│  ← signal no more messages
  │                                                  │
  │◄──── PushFailure (failed customer_ids, reason) ──│  ← zero or more
  │◄──── PushFailure ───────────────────────────────│
  │◄──── PushResponse (summary) ────────────────────│  ← exactly one, always last
  │                                                  │
  │◄──── EOF ───────────────────────────────────────│
```

**Rules:**

1. The **first** message on the stream **must** be a `StreamInit` (wrapped in `PushStreamMessage.init`).
2. All subsequent messages **must** be `PushRequest` (wrapped in `PushStreamMessage.push`).
3. After sending all push requests, the client **must** call `CloseSend()`.
4. The server may stream back zero or more `PushFailure` messages (for customer IDs that failed), followed by exactly one `PushResponse` summary.

---

## Authentication

The service uses **HTTP Basic Authentication** carried in gRPC metadata.

| Metadata key | Value format |
|-------------|--------------|
| `authorization` | `Basic <base64(api_key:api_secret)>` |

The credentials are validated against the database. Disabled API keys are rejected. After authentication, the server verifies that the `app_id` in `StreamInit` belongs to the authenticated organization.

**gRPC status codes for auth failures:**

| Scenario | gRPC Code |
|----------|-----------|
| Missing or malformed `authorization` header | `UNAUTHENTICATED` (16) |
| Invalid API key/secret | `UNAUTHENTICATED` (16) |
| Disabled API key | `UNAUTHENTICATED` (16) |
| `app_id` not owned by the org | `PERMISSION_DENIED` (7) |
| No push certificate configured for the app | `FAILED_PRECONDITION` (9) |

---

## Limits

| Constraint | Default | gRPC Error Code | Notes |
|-----------|---------|------------------|-------|
| Max messages per stream | **10,000** | `RESOURCE_EXHAUSTED` (8) | Total `PushRequest` messages in a single stream |
| Max customer IDs per message | **30,000** | `INVALID_ARGUMENT` (3) | `customer_ids` array length per `PushRequest` |
| Max stream duration | **600 seconds** (10 min) | `DEADLINE_EXCEEDED` (4) | Wall-clock timeout for the entire stream |
| Max receive message size | **4 MiB** | `RESOURCE_EXHAUSTED` (8) | default |
| `request_id` max length | **255 chars** | `INVALID_ARGUMENT` (3) | |
| `campaign_key` max length | **255 chars** | `INVALID_ARGUMENT` (3) | Must match `^[\w\-.]+$` |
| `campaign_key` format | alphanumeric, `_`, `-`, `.` | `INVALID_ARGUMENT` (3) | Regex: `^[\w\-.]+$` |



---

## Message Reference

### StreamInit

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `app_id` | `string` | **Yes** | Application UUID |
| `request_id` | `string` | No | Auto-generated UUID if omitted; max 255 chars |
| `campaign_key` | `string` | No | Max 255 chars; `^[\w\-.]+$` |
| `labels` | `Labels` | No | Up to 10 labels for tracking |
| `all_devices` | `bool` | No | Default `false` |
| `test` | `bool` | No | Test mode flag |

### PushRequest

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `customer_ids` | `repeated string` | **Yes** | Non-empty strings; max 30,000 per request |
| `alert` | `Alert` | **Yes** | Rejected if missing |
| `ios` | `IOSParams` | No | iOS-specific options |
| `android` | `AndroidParams` | No | Android-specific options |
| `web` | `WebParams` | No | Web push-specific options |

### Alert

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `body` | `string` | **Yes** | Notification body text |
| `title` | `string` | No | Short title |
| `subtitle` | `string` | No | iOS 10+ only; rejected if set without `title` |

### IOSParams

| Field | Type | Notes |
|-------|------|-------|
| `sound` | `string` | Sound file name or `"default"` |
| `badge` | `int32` | Badge count |
| `category` | `string` | Interactive push category |
| `content_available` | `bool` | Background fetch; true by default |
| `mutable_content` | `bool` | Notification extension; false by default |
| `extra` | `google.protobuf.Struct` | Arbitrary key-value JSON payload |

### AndroidParams

| Field | Type | Notes |
|-------|------|-------|
| `priority` | `string` | `"normal"` or `"high"` |
| `extra` | `google.protobuf.Struct` | Arbitrary key-value JSON payload |

### WebParams

| Field | Type | Notes |
|-------|------|-------|
| `badge` | `string` | URL to badge image |
| `dir` | `string` | `"ltr"`, `"rtl"`, or `"auto"` |
| `icon` | `string` | URL to icon image |
| `require_interaction` | `bool` | |
| `renotify` | `bool` | |
| `silent` | `bool` | |
| `tag` | `string` | Notification tag for grouping |
| `extra` | `google.protobuf.Struct` | Arbitrary key-value JSON payload |

### PushResponse (server → client, final summary)

| Field | Type | Notes |
|-------|------|-------|
| `request_id` | `string` | Matches the stream's request ID |
| `total_messages` | `int32` | Number of `PushRequest` messages processed |
| `total_customer_ids` | `int32` | Total customer IDs across all messages |
| `status` | `string` | `"accepted"` or `"error"` |
| `error` | `string` | Error description (only if `status == "error"`) |
| `campaign_id` | `int64` | Server-assigned campaign ID |

### PushFailure (server → client, zero or more)

| Field | Type | Notes |
|-------|------|-------|
| `customer_ids` | `repeated string` | Subset of IDs that failed |
| `reason` | `string` | Failure description |

---

## Client Examples

### Go

**Project setup:**

```bash
mkdir push-client && cd push-client
go mod init your-org/push-client

# Copy push.proto into the project
mkdir -p proto
cp /path/to/push.proto proto/

# Install protoc plugins
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Generate Go stubs
protoc \
  --go_out=. --go_opt=paths=source_relative \
  --go-grpc_out=. --go-grpc_opt=paths=source_relative \
  proto/push.proto

# Add gRPC dependencies
go get google.golang.org/grpc
go get google.golang.org/protobuf
```

This generates `proto/push.pb.go` and `proto/push_grpc.pb.go` in your project.

**Client code (`main.go`):**

```go
package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"time"

	pb "your-org/push-client/proto"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/structpb"
)

func main() {
	conn, err := grpc.NewClient("trans-api-grpc.sandbox53.localytics.com:50051",
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewPushServiceClient(conn)

	// Basic auth metadata
	auth := "Basic " + base64.StdEncoding.EncodeToString(
		[]byte("your-api-key:your-api-secret"))
	ctx := metadata.AppendToOutgoingContext(context.Background(),
		"authorization", auth)

	// Set a client-side deadline slightly above the server's 10-min max
	ctx, cancel := context.WithTimeout(ctx, 11*time.Minute)
	defer cancel()

	stream, err := client.StreamPush(ctx)
	if err != nil {
		log.Fatalf("open stream: %v", err)
	}

	// 1. Send StreamInit (must be first)
	campaignKey := "go-campaign-2026"
	if err := stream.Send(&pb.PushStreamMessage{
		Payload: &pb.PushStreamMessage_Init{
			Init: &pb.StreamInit{
				AppId:       "your-app-id",
				RequestId:   strPtr("req-go-001"),
				CampaignKey: &campaignKey,
				Labels: &pb.Labels{
					Label1: strPtr("promo"),
					Label2: strPtr("golang"),
				},
			},
		},
	}); err != nil {
		log.Fatalf("send init: %v", err)
	}

	// 2. Send push requests
	title := "Order Ready"
	if err := stream.Send(&pb.PushStreamMessage{
		Payload: &pb.PushStreamMessage_Push{
			Push: &pb.PushRequest{
				CustomerIds: []string{"user-alice", "user-bob"},
				Alert: &pb.Alert{
					Body:  "Your order is ready for pickup!",
					Title: &title,
				},
				Ios: &pb.IOSParams{
					Sound: strPtr("default"),
					Badge: int32Ptr(1),
				},
			},
		},
	}); err != nil {
		log.Fatalf("send push: %v", err)
	}

	// Send another with Android params and extra data
	extra, _ := structpb.NewStruct(map[string]interface{}{
		"deep_link":    "myapp://offers/123",
		"priority_tag": "high",
	})
	if err := stream.Send(&pb.PushStreamMessage{
		Payload: &pb.PushStreamMessage_Push{
			Push: &pb.PushRequest{
				CustomerIds: []string{"user-charlie"},
				Alert:       &pb.Alert{Body: "Flash sale: 50% off!"},
				Android: &pb.AndroidParams{
					Priority: strPtr("high"),
					Extra:    extra,
				},
			},
		},
	}); err != nil {
		log.Fatalf("send push: %v", err)
	}

	// 3. Close send side
	if err := stream.CloseSend(); err != nil {
		log.Fatalf("close send: %v", err)
	}

	// 4. Receive responses
	for {
		resp, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			log.Fatalf("recv: %v", err)
		}

		switch r := resp.Response.(type) {
		case *pb.PushStreamResponse_Failure:
			fmt.Printf("FAILURE: %d customer_ids failed: %s\n",
				len(r.Failure.CustomerIds), r.Failure.Reason)
		case *pb.PushStreamResponse_Summary:
			s := r.Summary
			fmt.Printf("SUMMARY: request_id=%s status=%s messages=%d customers=%d campaign_id=%d\n",
				s.RequestId, s.Status, s.TotalMessages, s.TotalCustomerIds, s.CampaignId)
			if s.Error != nil {
				fmt.Printf("  error: %s\n", *s.Error)
			}
		}
	}
}

func strPtr(s string) *string { return &s }
func int32Ptr(i int32) *int32 { return &i }
```

---

### Python

**Install dependencies:**

```bash
pip install grpcio grpcio-tools protobuf
```

**Generate Python stubs:**

```bash
python -m grpc_tools.protoc \
  -I proto \
  --python_out=./gen \
  --grpc_python_out=./gen \
  proto/push.proto
```

**Client code:**

```python
import base64
import grpc
from google.protobuf import struct_pb2

# Import generated stubs (adjust path based on your gen/ layout)
import push_pb2
import push_pb2_grpc


def create_auth_metadata(api_key: str, api_secret: str):
    """Create Basic auth metadata for gRPC calls."""
    credentials = base64.b64encode(f"{api_key}:{api_secret}".encode()).decode()
    return [("authorization", f"Basic {credentials}")]


def generate_requests():
    """Generator that yields StreamInit first, then PushRequests."""

    # 1. StreamInit — must be the first message
    yield push_pb2.PushStreamMessage(
        init=push_pb2.StreamInit(
            app_id="your-app-id",
            request_id="req-python-001",
            campaign_key="summer-sale-2026",
            labels=push_pb2.Labels(label1="promo", label2="python"),
        )
    )

    # 2. PushRequest messages
    yield push_pb2.PushStreamMessage(
        push=push_pb2.PushRequest(
            customer_ids=["user-alice", "user-bob"],
            alert=push_pb2.Alert(
                body="Your order is ready for pickup!",
                title="Order Ready",
            ),
            ios=push_pb2.IOSParams(
                sound="default",
                badge=1,
            ),
        )
    )

    # 3. Another PushRequest with Android params and extra data
    extra = struct_pb2.Struct()
    extra.update({"deep_link": "myapp://offers/123", "priority_tag": "high"})

    yield push_pb2.PushStreamMessage(
        push=push_pb2.PushRequest(
            customer_ids=["user-charlie", "user-dave"],
            alert=push_pb2.Alert(
                body="Flash sale: 50% off everything!",
            ),
            android=push_pb2.AndroidParams(
                priority="high",
                extra=extra,
            ),
        )
    )


def main():
    # Use insecure channel for local dev; use grpc.secure_channel() in production
    channel = grpc.insecure_channel("trans-api-grpc.sandbox53.localytics.com:50051")
    stub = push_pb2_grpc.PushServiceStub(channel)

    metadata = create_auth_metadata("your-api-key", "your-api-secret")

    # Open bidirectional stream
    responses = stub.StreamPush(generate_requests(), metadata=metadata)

    # Process server responses
    for response in responses:
        if response.HasField("failure"):
            failure = response.failure
            print(
                f"FAILURE: {len(failure.customer_ids)} IDs failed — {failure.reason}"
            )
            print(f"  Failed IDs: {list(failure.customer_ids)}")

        elif response.HasField("summary"):
            summary = response.summary
            print(f"SUMMARY:")
            print(f"  request_id:         {summary.request_id}")
            print(f"  status:             {summary.status}")
            print(f"  total_messages:     {summary.total_messages}")
            print(f"  total_customer_ids: {summary.total_customer_ids}")
            print(f"  campaign_id:        {summary.campaign_id}")
            if summary.error:
                print(f"  error:              {summary.error}")

    channel.close()


if __name__ == "__main__":
    main()
```

**Python with async (grpcio-aio):**

```python
import asyncio
import base64
import grpc.aio

import push_pb2
import push_pb2_grpc


async def main():
    credentials = base64.b64encode(b"your-api-key:your-api-secret").decode()
    metadata = [("authorization", f"Basic {credentials}")]

    async with grpc.aio.insecure_channel("trans-api-grpc.sandbox53.localytics.com:50051") as channel:
        stub = push_pb2_grpc.PushServiceStub(channel)
        stream = stub.StreamPush(metadata=metadata)

        # Send init
        await stream.write(
            push_pb2.PushStreamMessage(
                init=push_pb2.StreamInit(app_id="your-app-id")
            )
        )

        # Send push
        await stream.write(
            push_pb2.PushStreamMessage(
                push=push_pb2.PushRequest(
                    customer_ids=["user-1"],
                    alert=push_pb2.Alert(body="Hello from async Python!"),
                )
            )
        )

        # Signal done sending
        await stream.done_writing()

        # Read responses
        async for response in stream:
            if response.HasField("failure"):
                print(f"Failure: {response.failure.reason}")
            elif response.HasField("summary"):
                print(f"Summary: {response.summary.status}")


asyncio.run(main())
```

---

### Node.js (JavaScript / TypeScript)

**Install dependencies:**

```bash
npm install @grpc/grpc-js @grpc/proto-loader
```

**Client code (`client.js`):**

```javascript
const grpc = require("@grpc/grpc-js");
const protoLoader = require("@grpc/proto-loader");
const path = require("path");

// Load the proto file
const packageDef = protoLoader.loadSync(
  path.join(__dirname, "proto", "push.proto"),
  {
    keepCase: true,
    longs: String,
    enums: String,
    defaults: true,
    oneofs: true,
  }
);
const proto = grpc.loadPackageDefinition(packageDef).push;

// Create client
const client = new proto.PushService(
  "trans-api-grpc.sandbox53.localytics.com:50051",
  grpc.credentials.createInsecure()
);

// Basic auth metadata
const apiKey = "your-api-key";
const apiSecret = "your-api-secret";
const authValue =
  "Basic " + Buffer.from(`${apiKey}:${apiSecret}`).toString("base64");
const metadata = new grpc.Metadata();
metadata.add("authorization", authValue);

// Open bidirectional stream
const stream = client.StreamPush(metadata);

// Handle server responses
stream.on("data", (response) => {
  if (response.failure) {
    console.log("FAILURE:", {
      customer_ids: response.failure.customer_ids,
      reason: response.failure.reason,
    });
  } else if (response.summary) {
    console.log("SUMMARY:", {
      request_id: response.summary.request_id,
      status: response.summary.status,
      total_messages: response.summary.total_messages,
      total_customer_ids: response.summary.total_customer_ids,
      campaign_id: response.summary.campaign_id,
      error: response.summary.error,
    });
  }
});

stream.on("error", (err) => {
  console.error("Stream error:", err.message);
  console.error("Code:", err.code, "Details:", err.details);
});

stream.on("end", () => {
  console.log("Stream ended.");
});

// 1. Send StreamInit
stream.write({
  init: {
    app_id: "your-app-id",
    request_id: "req-node-001",
    campaign_key: "node-test-campaign",
    labels: { label1: "promo", label2: "nodejs" },
    all_devices: false,
  },
});

// 2. Send PushRequests
stream.write({
  push: {
    customer_ids: ["user-alice", "user-bob"],
    alert: { body: "Your order is ready!", title: "Order Ready" },
    ios: { sound: "default", badge: 1 },
  },
});

stream.write({
  push: {
    customer_ids: ["user-charlie"],
    alert: { body: "Flash sale: 50% off!" },
    android: {
      priority: "high",
      extra: {
        fields: {
          deep_link: { stringValue: "myapp://offers/123" },
        },
      },
    },
  },
});

// 3. Close send side
stream.end();
```

---

### Java

**Add dependencies (Gradle):**

```groovy
dependencies {
    implementation 'io.grpc:grpc-netty-shaded:1.68.0'
    implementation 'io.grpc:grpc-protobuf:1.68.0'
    implementation 'io.grpc:grpc-stub:1.68.0'
    implementation 'com.google.protobuf:protobuf-java:4.29.0'
}
```

**Generate Java stubs with `protoc`:**

```bash
protoc --java_out=src/main/java --grpc-java_out=src/main/java proto/push.proto
```

**Client code:**

```java
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import push.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;

public class PushClient {

    public static void main(String[] args) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("trans-api-grpc.sandbox53.localytics.com", 50051)
                .usePlaintext()
                .build();

        PushServiceGrpc.PushServiceStub asyncStub = PushServiceGrpc.newStub(channel);

        // Basic auth metadata
        String credentials = Base64.getEncoder().encodeToString(
                "your-api-key:your-api-secret".getBytes(StandardCharsets.UTF_8));
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Basic " + credentials);
        asyncStub = MetadataUtils.attachHeaders(asyncStub, headers);

        CountDownLatch latch = new CountDownLatch(1);

        // Response observer
        StreamObserver<Push.PushStreamResponse> responseObserver =
                new StreamObserver<>() {
                    @Override
                    public void onNext(Push.PushStreamResponse response) {
                        if (response.hasFailure()) {
                            Push.PushFailure f = response.getFailure();
                            System.out.printf("FAILURE: %d IDs failed — %s%n",
                                    f.getCustomerIdsCount(), f.getReason());
                        } else if (response.hasSummary()) {
                            Push.PushResponse s = response.getSummary();
                            System.out.printf("SUMMARY: request_id=%s status=%s messages=%d customers=%d%n",
                                    s.getRequestId(), s.getStatus(),
                                    s.getTotalMessages(), s.getTotalCustomerIds());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        Status status = Status.fromThrowable(t);
                        System.err.printf("ERROR: code=%s desc=%s%n",
                                status.getCode(), status.getDescription());
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("Stream completed.");
                        latch.countDown();
                    }
                };

        // Open stream and get request observer
        StreamObserver<Push.PushStreamMessage> requestObserver =
                asyncStub.streamPush(responseObserver);

        // 1. Send StreamInit
        requestObserver.onNext(Push.PushStreamMessage.newBuilder()
                .setInit(Push.StreamInit.newBuilder()
                        .setAppId("your-app-id")
                        .setRequestId("req-java-001")
                        .setCampaignKey("java-test")
                        .setLabels(Push.Labels.newBuilder()
                                .setLabel1("promo")
                                .build())
                        .build())
                .build());

        // 2. Send PushRequest
        requestObserver.onNext(Push.PushStreamMessage.newBuilder()
                .setPush(Push.PushRequest.newBuilder()
                        .addCustomerIds("user-alice")
                        .addCustomerIds("user-bob")
                        .setAlert(Push.Alert.newBuilder()
                                .setBody("Your order is ready!")
                                .setTitle("Order Ready")
                                .build())
                        .setIos(Push.IOSParams.newBuilder()
                                .setSound("default")
                                .setBadge(1)
                                .build())
                        .build())
                .build());

        // 3. Signal done
        requestObserver.onCompleted();

        latch.await();
        channel.shutdown();
    }
}
```

---

### cURL / grpcurl

[`grpcurl`](https://github.com/fullstorydev/grpcurl) can interact with the service since **reflection is enabled**.

**Install:**

```bash
brew install grpcurl          # macOS
go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest  # Go
```

**List services:**

```bash
grpcurl -plaintext trans-api-grpc.sandbox53.localytics.com:50051 list
# push.PushService
# grpc.health.v1.Health
```

**Describe the service:**

```bash
grpcurl -plaintext trans-api-grpc.sandbox53.localytics.com:50051 describe push.PushService
```

**Send a streaming request (using stdin):**

`grpcurl` does not natively support interactive bidirectional streaming well, but you can pipe JSON messages:

```bash
echo '
{"init": {"app_id": "your-app-id", "request_id": "req-grpcurl-001"}}
{"push": {"customer_ids": ["user-alice"], "alert": {"body": "Hello from grpcurl!", "title": "Test"}}}
' | grpcurl \
  -plaintext \
  -d @ \
  -rpc-header "authorization: Basic $(echo -n 'your-api-key:your-api-secret' | base64)" \
  trans-api-grpc.sandbox53.localytics.com:50051 \
  push.PushService/StreamPush
```

**Using a proto file instead of reflection:**

```bash
grpcurl \
  -plaintext \
  -import-path proto \
  -proto push.proto \
  -rpc-header "authorization: Basic $(echo -n 'key:secret' | base64)" \
  -d '{"init": {"app_id": "your-app-id"}}
      {"push": {"customer_ids": ["user-1"], "alert": {"body": "Test"}}}' \
  trans-api-grpc.sandbox53.localytics.com:50051 \
  push.PushService/StreamPush
```

---

## Error Handling

### gRPC Status Codes

The server returns standard gRPC status codes. Handle these in your client:

| Code | Name | When |
|------|------|------|
| 0 | `OK` | Stream completed successfully |
| 3 | `INVALID_ARGUMENT` | Bad `request_id`, `campaign_key` format, missing `alert`, `subtitle` without `title`, empty `customer_ids`, too many customer IDs per message |
| 4 | `DEADLINE_EXCEEDED` | Stream exceeded max duration (default 10 min) |
| 7 | `PERMISSION_DENIED` | `app_id` not owned by the authenticated org |
| 8 | `RESOURCE_EXHAUSTED` | Exceeded max messages per stream, or message too large |
| 9 | `FAILED_PRECONDITION` | No push certificate configured for the app |
| 13 | `INTERNAL` | Server-side error |
| 16 | `UNAUTHENTICATED` | Missing, malformed, or invalid credentials |

### In-stream Failures vs. gRPC Errors

There are two kinds of errors:

1. **gRPC-level errors** — The stream itself terminates with an error status. This means something went wrong with auth, validation, or the server. You receive this as an error from `Recv()` (Go), `onError` (Java), or a raised exception (Python).

2. **In-stream `PushFailure` messages** — The stream stays open, but some customer IDs could not be processed. These are normal `PushStreamResponse` messages with the `failure` oneof populated. The stream still closes with a successful `PushResponse` summary afterward.

**Always read all responses until EOF** — even if you receive failures, the summary is coming.

---

## Best Practices

### Connection Management

- **Reuse connections.** Create one `grpc.ClientConn` (Go) / `ManagedChannel` (Java) / `grpc.Channel` (Python) and share it across multiple streams. gRPC handles HTTP/2 multiplexing.
- **Set deadlines.** The server enforces a 10-minute stream timeout, but clients should set their own context deadline slightly above that as a safety net.

### Batching

- **Maximize `customer_ids` per `PushRequest`.** Sending 10,000 IDs in one message is far more efficient than 10,000 messages with 1 ID each.
- **Stay within limits.** Don't exceed 25,000 customer IDs per message or 10,000 messages per stream.

### Parallelism

- **Open multiple streams** for large campaigns. Each stream gets its own `request_id` and independent limits. The server handles concurrent streams.

### Resilience

- **Retry with backoff** on `UNAVAILABLE` (14) or `DEADLINE_EXCEEDED` (4). These are transient.
- **Do not retry** on `UNAUTHENTICATED`, `PERMISSION_DENIED`, or `INVALID_ARGUMENT` — fix the request first.
- **Handle `PushFailure`** messages by collecting the failed customer IDs and retrying them in a new stream.

### The `extra` Fields

The `ios.extra`, `android.extra`, and `web.extra` fields use `google.protobuf.Struct`, which represents arbitrary JSON. This enables nested objects, not just flat string maps. Use your language's Struct builder:

| Language | Struct Construction |
|----------|-------------------|
| **Go** | `structpb.NewStruct(map[string]interface{}{...})` |
| **Python** | `struct_pb2.Struct()` then `s.update({...})` |
| **Java** | `Struct.newBuilder().putFields("key", Value.newBuilder()...)` |
| **Node.js** | `{ fields: { key: { stringValue: "val" } } }` |
