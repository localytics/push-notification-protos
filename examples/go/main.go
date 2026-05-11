package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"os"
	"time"

	pb "example.com/pushclient/gen/push"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
)

const endpoint = "trans-api-grpc.sandbox53.localytics.com:50051"

func main() {
	apiKey := mustEnv("PUSH_API_KEY")
	apiSecret := mustEnv("PUSH_API_SECRET")
	appID := mustEnv("PUSH_APP_ID")

	conn, err := grpc.NewClient(endpoint,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewPushServiceClient(conn)

	auth := "Basic " + base64.StdEncoding.EncodeToString([]byte(apiKey+":"+apiSecret))
	ctx := metadata.AppendToOutgoingContext(context.Background(), "authorization", auth)
	ctx, cancel := context.WithTimeout(ctx, 11*time.Minute)
	defer cancel()

	stream, err := client.StreamPush(ctx)
	if err != nil {
		log.Fatalf("open stream: %v", err)
	}

	if err := stream.Send(&pb.PushStreamMessage{
		Payload: &pb.PushStreamMessage_Init{
			Init: &pb.StreamInit{AppId: appID},
		},
	}); err != nil {
		log.Fatalf("send init: %v", err)
	}

	title := "Hello"
	if err := stream.Send(&pb.PushStreamMessage{
		Payload: &pb.PushStreamMessage_Push{
			Push: &pb.PushRequest{
				CustomerIds: []string{"user-1", "user-2"},
				Alert: &pb.Alert{
					Body:  "Hello from Go!",
					Title: &title,
				},
			},
		},
	}); err != nil {
		log.Fatalf("send push: %v", err)
	}

	if err := stream.CloseSend(); err != nil {
		log.Fatalf("close send: %v", err)
	}

	for {
		resp, err := stream.Recv()
		if err == io.EOF {
			return
		}
		if err != nil {
			log.Fatalf("recv: %v", err)
		}
		switch r := resp.Response.(type) {
		case *pb.PushStreamResponse_Failure:
			fmt.Printf("failure: %d ids: %s\n", len(r.Failure.CustomerIds), r.Failure.Reason)
		case *pb.PushStreamResponse_Summary:
			s := r.Summary
			fmt.Printf("summary: request_id=%s status=%s messages=%d customers=%d campaign=%d\n",
				s.RequestId, s.Status, s.TotalMessages, s.TotalCustomerIds, s.CampaignId)
			if s.Error != nil {
				fmt.Printf("  error: %s\n", *s.Error)
			}
		}
	}
}

func mustEnv(name string) string {
	v := os.Getenv(name)
	if v == "" {
		log.Fatalf("%s not set", name)
	}
	return v
}
