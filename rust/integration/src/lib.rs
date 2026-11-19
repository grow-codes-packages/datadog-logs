// Created by sdk-kit 0.1.0; owned by this repository.
//! Helpers for the integration tests: bootstrap credentials, the conformance directory and the
//! `datadog/fakeintake` harness (raw payload read-back, response overrides, and a
//! `LogQueryClient` over the captured payloads for the conformance logging scenarios).

use std::io::Read;
use std::path::PathBuf;
use std::time::{Duration, UNIX_EPOCH};

use async_trait::async_trait;
use grow_datadog_logs as sdk;
use sdk::logging::{LogQueryClient, LogRecord, LogSearchResult, SearchOptions, Severity};
use sdk::{Error, ErrorCode, Result};
use serde_json::{json, Value};

/// Loads `KEY=value` lines from `$GROW_BOOTSTRAP_ENV` (default `/bootstrap/env`) into the
/// process environment; variables that are already set win. A missing file is not an error.
pub fn load_bootstrap_env() {
    let path = std::env::var("GROW_BOOTSTRAP_ENV").unwrap_or_else(|_| "/bootstrap/env".to_owned());
    let Ok(text) = std::fs::read_to_string(path) else {
        return;
    };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            let key = key.trim().trim_start_matches("export ").trim();
            let value = value.trim().trim_matches('"');
            if !key.is_empty() && std::env::var_os(key).is_none() {
                std::env::set_var(key, value);
            }
        }
    }
}

/// `$GROW_CONFORMANCE_DIR`, else `<repo>/conformance/scenarios`.
pub fn conformance_dir() -> PathBuf {
    sdk::testing::scenarios_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/.."))
}

/// Loads the bootstrap env; `false` (test should return) outside docker/compose.yml.
pub fn setup() -> bool {
    load_bootstrap_env();
    if std::env::var("GROW_DATADOG_LOGS_URL")
        .unwrap_or_default()
        .is_empty()
    {
        eprintln!("GROW_DATADOG_LOGS_URL is not set; run through docker/compose.yml");
        return false;
    }
    true
}

/// A short unique marker for one test.
pub fn run_id() -> String {
    format!("rust-{}", sdk::testing::run_id())
}

fn b64_decode(input: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0u32);
    for c in input.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            _ => continue,
        };
        acc = (acc << 6) | u32::from(v);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    out
}

fn b64_encode(data: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(T[((n >> (18 - 6 * i)) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// One captured request body (decoded).
#[derive(Debug, Clone)]
pub struct Payload {
    /// Decompressed body.
    pub raw: Vec<u8>,
    /// The logs of a JSON array body (or the single object).
    pub logs: Vec<Value>,
}

/// Talks to the fakeintake harness endpoints (not Datadog APIs).
#[derive(Debug, Clone)]
pub struct FakeIntake {
    base: String,
    http: reqwest::Client,
}

impl Default for FakeIntake {
    fn default() -> Self {
        Self::new()
    }
}

impl FakeIntake {
    /// `$FAKEINTAKE_URL`, else `$GROW_DATADOG_LOGS_URL`.
    pub fn new() -> Self {
        let base = std::env::var("FAKEINTAKE_URL")
            .or_else(|_| std::env::var("GROW_DATADOG_LOGS_URL"))
            .unwrap_or_default();
        FakeIntake {
            base: base.trim_end_matches('/').to_owned(),
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(60))
                .build()
                .expect("http client"),
        }
    }

    /// Every captured body of `endpoint`, decoded from the raw view.
    pub async fn payloads(&self, endpoint: &str) -> Result<Vec<Payload>> {
        let fail = |what: String| Error::unavailable(format!("fakeintake payloads: {what}"));
        let resp = self
            .http
            .get(format!("{}/fakeintake/payloads", self.base))
            .query(&[("endpoint", endpoint)])
            .send()
            .await
            .map_err(|e| fail(e.to_string()))?;
        if !resp.status().is_success() {
            return Err(fail(format!("HTTP {}", resp.status())));
        }
        let body: Value = resp.json().await.map_err(|e| fail(e.to_string()))?;
        let mut out = Vec::new();
        for p in body["payloads"].as_array().cloned().unwrap_or_default() {
            let data = b64_decode(p["data"].as_str().unwrap_or_default());
            let raw = match p["encoding"].as_str().unwrap_or_default() {
                "gzip" => {
                    let mut v = Vec::new();
                    flate2::read::GzDecoder::new(&data[..])
                        .read_to_end(&mut v)
                        .map_err(|e| fail(e.to_string()))?;
                    v
                }
                "deflate" => {
                    let mut v = Vec::new();
                    flate2::read::ZlibDecoder::new(&data[..])
                        .read_to_end(&mut v)
                        .map_err(|e| fail(e.to_string()))?;
                    v
                }
                _ => data,
            };
            let logs = match serde_json::from_slice::<Value>(&raw) {
                Ok(Value::Array(items)) => items.into_iter().filter(Value::is_object).collect(),
                Ok(obj @ Value::Object(_)) => vec![obj],
                _ => Vec::new(),
            };
            out.push(Payload { raw, logs });
        }
        Ok(out)
    }

    /// Makes `method endpoint` answer `status`/`body` (payloads are still captured).
    pub async fn override_response(&self, method: &str, endpoint: &str, status: u16, body: &str) {
        let spec = json!({
            "method": method, "endpoint": endpoint, "status_code": status,
            "content_type": "application/json", "body": b64_encode(body.as_bytes()),
        });
        let resp = self
            .http
            .post(format!("{}/fakeintake/configure/override", self.base))
            .json(&spec)
            .send()
            .await
            .expect("override request");
        assert!(
            resp.status().is_success(),
            "override {method} {endpoint}: {}",
            resp.status()
        );
    }

    /// Captured intake logs whose message contains `marker`, and the payloads holding them.
    pub async fn logs_with(&self, marker: &str) -> (Vec<Value>, Vec<Payload>) {
        let payloads = self.payloads("/api/v2/logs").await.expect("payloads");
        let mut logs = Vec::new();
        let mut hit = Vec::new();
        for p in payloads {
            let found: Vec<Value> = p
                .logs
                .iter()
                .filter(|l| l["message"].as_str().unwrap_or("").contains(marker))
                .cloned()
                .collect();
            if !found.is_empty() {
                logs.extend(found);
                hit.push(p);
            }
        }
        (logs, hit)
    }
}

/// [`LogQueryClient`] over the fakeintake capture: `search` returns the captured intake logs
/// whose message contains every term of the query (quotes ignored), converted back to
/// [`LogRecord`]s. It lets the conformance logging scenarios assert send → read back against
/// the real container; Datadog's search API itself is not available there.
#[derive(Debug, Clone, Default)]
pub struct FakeIntakeQuery {
    /// The harness.
    pub intake: FakeIntake,
}

/// Turns a captured Datadog log back into a [`LogRecord`].
pub fn captured_record(log: &Value) -> LogRecord {
    let mut rec = LogRecord::new(Severity::Info, "");
    rec.severity_text = String::new();
    for (k, v) in log.as_object().cloned().unwrap_or_default() {
        match k.as_str() {
            "message" => rec.body = v.as_str().unwrap_or_default().to_owned(),
            "status" => {
                rec.severity_text = v.as_str().unwrap_or_default().to_owned();
                rec.severity = rec.severity_text.parse().unwrap_or(Severity::Info);
            }
            "timestamp" => {
                rec.timestamp = UNIX_EPOCH + Duration::from_millis(v.as_u64().unwrap_or(0))
            }
            "service" => {
                rec.resource.insert("service.name".into(), v);
            }
            "hostname" => {
                rec.resource.insert("host.name".into(), v);
            }
            "otel.trace_id" => rec.trace_id = v.as_str().map(str::to_owned),
            "otel.span_id" => rec.span_id = v.as_str().map(str::to_owned),
            _ => {
                rec.attributes.insert(k, v);
            }
        }
    }
    rec
}

#[async_trait]
impl LogQueryClient for FakeIntakeQuery {
    async fn search(&self, query: &str, _opts: SearchOptions) -> Result<LogSearchResult> {
        let terms: Vec<String> = query
            .replace('"', " ")
            .split_whitespace()
            .map(str::to_owned)
            .collect();
        let payloads = self.intake.payloads("/api/v2/logs").await?;
        let records = payloads
            .iter()
            .flat_map(|p| p.logs.iter())
            .filter(|l| {
                let msg = l["message"].as_str().unwrap_or("");
                terms.iter().all(|t| msg.contains(t.as_str()))
            })
            .map(captured_record)
            .collect();
        Ok(LogSearchResult {
            records,
            next_cursor: None,
            total: None,
        })
    }
}

/// Asserts an error code with a readable message.
pub fn assert_code<T: std::fmt::Debug>(result: Result<T>, code: ErrorCode) -> Error {
    let err = result.expect_err("expected an error");
    assert_eq!(err.code(), code, "{err}");
    err
}
