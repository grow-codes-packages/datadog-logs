//! Service-owned helpers for the Datadog unit tests: recorded fixtures and mock clients.
#![allow(dead_code)]

use std::io::Read;
use std::time::Duration;

use grow_datadog_logs as sdk;
use sdk::core::{RandomSource, RetryPolicy};
use sdk::{DatadogLogsClient, DatadogLogsConfig, DatadogLogsConfigBuilder};
use serde_json::Value;
use wiremock::{MockServer, Request, ResponseTemplate};

/// Test-only placeholder keys (never real credentials).
pub const API_KEY: &str = "test-api-key-0001";
pub const APP_KEY: &str = "test-app-key-0002";

/// Loads `tests/fixtures/datadog/<name>.json`.
pub fn fixture(name: &str) -> Value {
    let path = format!(
        "{}/tests/fixtures/datadog/{name}.json",
        env!("CARGO_MANIFEST_DIR")
    );
    let text = std::fs::read_to_string(&path).unwrap_or_else(|_| panic!("fixture {path}"));
    serde_json::from_str(&text).unwrap()
}

/// The recorded response of a fixture as a wiremock response (status, headers, body).
pub fn respond(name: &str) -> ResponseTemplate {
    let f = fixture(name);
    let resp = &f["response"];
    let status = resp["status"].as_u64().unwrap() as u16;
    let mut t = ResponseTemplate::new(status);
    if let Some(headers) = resp["headers"].as_object() {
        for (k, v) in headers {
            if !matches!(
                k.as_str(),
                "content-type" | "content-length" | "transfer-encoding" | "content-encoding"
            ) {
                t = t.insert_header(k.as_str(), v.as_str().unwrap_or_default());
            }
        }
    }
    match &resp["body"] {
        Value::Null => t,
        Value::String(s) => t.set_body_string(s.clone()),
        body => t.set_body_json(body.clone()),
    }
}

/// Retry policy with short backoffs and deterministic jitter.
pub fn fast_retry(max_retries: u32) -> RetryPolicy {
    RetryPolicy {
        max_retries,
        initial_backoff: Duration::from_millis(5),
        max_backoff: Duration::from_millis(20),
        ..RetryPolicy::default()
    }
    .with_random(RandomSource::fixed(0.0))
}

/// Builder pointing both hosts at the mock server, with both keys.
pub fn builder(server: &MockServer) -> DatadogLogsConfigBuilder {
    DatadogLogsConfig::builder()
        .url(server.uri())
        .api_key(API_KEY)
        .app_key(APP_KEY)
        .retry(fast_retry(2))
}

/// Client pointing at the mock server.
pub fn client(server: &MockServer) -> DatadogLogsClient {
    DatadogLogsClient::new(builder(server).build().unwrap()).unwrap()
}

/// Decodes a request body (gzip when `content-encoding: gzip`) as JSON.
pub fn body_json(req: &Request) -> Value {
    let gz = req
        .headers
        .get("content-encoding")
        .is_some_and(|v| v.to_str().unwrap_or("") == "gzip");
    let raw = if gz {
        let mut out = Vec::new();
        flate2::read::GzDecoder::new(&req.body[..])
            .read_to_end(&mut out)
            .unwrap();
        out
    } else {
        req.body.clone()
    };
    serde_json::from_slice(&raw).unwrap()
}

/// Every request received on `path`.
pub async fn requests(server: &MockServer, path: &str) -> Vec<Request> {
    server
        .received_requests()
        .await
        .unwrap_or_default()
        .into_iter()
        .filter(|r| r.url.path() == path)
        .collect()
}
