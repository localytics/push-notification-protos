package com.example.pushclient;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import push.Push.Alert;
import push.Push.PushFailure;
import push.Push.PushRequest;
import push.Push.PushResponse;
import push.Push.PushStreamMessage;
import push.Push.PushStreamResponse;
import push.Push.StreamInit;
import push.PushServiceGrpc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PushClient {

    private static final String HOST = "trans-api-grpc.sandbox53.localytics.com";
    private static final int PORT = 50051;

    public static void main(String[] args) throws Exception {
        String apiKey = requireEnv("PUSH_API_KEY");
        String apiSecret = requireEnv("PUSH_API_SECRET");
        String appId = requireEnv("PUSH_APP_ID");

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(HOST, PORT)
                .usePlaintext()
                .build();

        String credentials = Base64.getEncoder().encodeToString(
                (apiKey + ":" + apiSecret).getBytes(StandardCharsets.UTF_8));
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Basic " + credentials);

        PushServiceGrpc.PushServiceStub stub = PushServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        CountDownLatch done = new CountDownLatch(1);

        StreamObserver<PushStreamResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(PushStreamResponse response) {
                if (response.hasFailure()) {
                    PushFailure f = response.getFailure();
                    System.out.printf("failure: %d ids: %s%n",
                            f.getCustomerIdsCount(), f.getReason());
                } else if (response.hasSummary()) {
                    PushResponse s = response.getSummary();
                    System.out.printf(
                            "summary: request_id=%s status=%s messages=%d customers=%d campaign=%d%n",
                            s.getRequestId(), s.getStatus(),
                            s.getTotalMessages(), s.getTotalCustomerIds(), s.getCampaignId());
                    if (s.hasError()) {
                        System.out.println("  error: " + s.getError());
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("error: " + t.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };

        StreamObserver<PushStreamMessage> requestObserver = stub.streamPush(responseObserver);

        requestObserver.onNext(PushStreamMessage.newBuilder()
                .setInit(StreamInit.newBuilder()
                        .setAppId(appId)
                        .build())
                .build());

        requestObserver.onNext(PushStreamMessage.newBuilder()
                .setPush(PushRequest.newBuilder()
                        .addCustomerIds("user-1")
                        .addCustomerIds("user-2")
                        .setAlert(Alert.newBuilder()
                                .setBody("Hello from Java!")
                                .setTitle("Hello")
                                .build())
                        .build())
                .build());

        requestObserver.onCompleted();

        if (!done.await(11, TimeUnit.MINUTES)) {
            System.err.println("timed out waiting for server");
        }
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            System.err.println(name + " not set");
            System.exit(1);
        }
        return v;
    }
}
