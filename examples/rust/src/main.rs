use std::env;

use base64::{engine::general_purpose, Engine as _};
use tokio_stream::StreamExt;
use tonic::{metadata::MetadataValue, transport::Channel, Request};

pub mod push {
    tonic::include_proto!("push");
}

use push::push_service_client::PushServiceClient;
use push::{
    push_stream_message, push_stream_response, Alert, PushRequest, PushStreamMessage, StreamInit,
};

const ENDPOINT: &str = "http://trans-api-grpc.sandbox53.localytics.com:50051";

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let api_key = require_env("PUSH_API_KEY");
    let api_secret = require_env("PUSH_API_SECRET");
    let app_id = require_env("PUSH_APP_ID");

    let channel = Channel::from_static(ENDPOINT).connect().await?;
    let mut client = PushServiceClient::new(channel);

    let auth_value: MetadataValue<_> = format!(
        "Basic {}",
        general_purpose::STANDARD.encode(format!("{api_key}:{api_secret}"))
    )
    .parse()?;

    let outbound = async_stream::stream! {
        yield PushStreamMessage {
            payload: Some(push_stream_message::Payload::Init(StreamInit {
                app_id: app_id.clone(),
                request_id: None,
                campaign_key: None,
                labels: None,
                all_devices: None,
                test: None,
            })),
        };
        yield PushStreamMessage {
            payload: Some(push_stream_message::Payload::Push(PushRequest {
                customer_ids: vec!["user-1".into(), "user-2".into()],
                alert: Some(Alert {
                    body: "Hello from Rust!".into(),
                    title: Some("Hello".into()),
                    subtitle: None,
                }),
                ios: None,
                android: None,
                web: None,
            })),
        };
    };

    let mut request = Request::new(outbound);
    request
        .metadata_mut()
        .insert("authorization", auth_value);

    let response = client.stream_push(request).await?;
    let mut inbound = response.into_inner();

    while let Some(msg) = inbound.next().await {
        let msg = msg?;
        match msg.response {
            Some(push_stream_response::Response::Failure(f)) => {
                println!(
                    "failure: {} ids: {}",
                    f.customer_ids.len(),
                    f.reason
                );
            }
            Some(push_stream_response::Response::Summary(s)) => {
                println!(
                    "summary: request_id={} status={} messages={} customers={} campaign={}",
                    s.request_id,
                    s.status,
                    s.total_messages,
                    s.total_customer_ids,
                    s.campaign_id
                );
                if let Some(err) = s.error.as_deref() {
                    println!("  error: {err}");
                }
            }
            None => {}
        }
    }

    Ok(())
}

fn require_env(name: &str) -> String {
    env::var(name).unwrap_or_else(|_| {
        eprintln!("{name} not set");
        std::process::exit(1);
    })
}
