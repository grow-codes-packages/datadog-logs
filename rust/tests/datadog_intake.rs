//! Logs intake: `log_client`, the exporter, limits, truncation, gzip and error mapping.

mod dd;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use dd::{body_json, builder, client, fast_retry, requests, respond, API_KEY};
use grow_datadog_logs as sdk;
use sdk::core::ErrorCode;
use sdk::logging::{
    attrs, BatchOptions, ExportOptions, LogClient, LogEntry, LogExporter, LogInput, LogRecord,
    Severity,
};
use sdk::mapping::{
    MAX_ENTRIES_PER_REQUEST, MAX_LOG_BYTES, MAX_PAYLOAD_BYTES, TRUNCATED_ATTRIBUTE,
};
use sdk::{DatadogLogsClient, LogClientOptions};
use serde_json::{json, Value};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

async fn intake(status: u16) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/v2/logs"))
        .respond_with(ResponseTemplate::new(status).set_body_json(json!({})))
        .mount(&server)
        .await;
    server
}

async fn sent_logs(server: &MockServer) -> Vec<Value> {
    requests(server, "/api/v2/logs")
        .await
        .iter()
        .flat_map(|r| body_json(r).as_array().cloned().unwrap_or_default())
        .collect()
}

fn find<'a>(logs: &'a [Value], prefix: &str) -> &'a Value {
    logs.iter()
        .find(|l| l["message"].as_str().unwrap_or("").starts_with(prefix))
        .unwrap_or_else(|| panic!("no log {prefix}: {logs:?}"))
}

#[tokio::test]
async fn log_client_round_trip() {
    let server = intake(202).await;
    let c = DatadogLogsClient::new(
        builder(&server)
            .service("cfg-svc")
            .env("it")
            .version("1.2.3")
            .hostname("rust-host")
            .tags(["team:sdk", "lang:rust", "bad tag,x"])
            .build()
            .unwrap(),
    )
    .unwrap();
    let opts = LogClientOptions::default()
        .batch(
            BatchOptions::default()
                .flush_interval(Duration::from_secs(3600))
                .max_batch_size(5000)
                .max_batch_bytes(50_000_000),
        )
        .resource_attribute("team.owner", "sdk");
    assert!(format!("{opts:?}").contains("LogClientOptions"));
    let lc = c.log_client(opts).unwrap();
    assert_eq!(
        lc.processor().options().max_batch_size,
        MAX_ENTRIES_PER_REQUEST
    );
    assert_eq!(lc.processor().options().max_batch_bytes, MAX_PAYLOAD_BYTES);
    lc.send(LogEntry::Input(
        LogInput::new("input run-1")
            .severity(Severity::Warn)
            .attribute("service", "user-value")
            .attribute("password", "hunter2")
            .attribute("user", json!({"id": 42})),
    ));
    let mut rec = LogRecord::new(
        Severity::Error,
        "record run-1 Grüße – 日本語 \"quoted\"\u{2028}",
    )
    .with_trace("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
    rec.resource
        .insert("service.name".into(), "checkout".into());
    rec.resource
        .insert("deployment.environment".into(), "test".into());
    lc.send_batch(vec![LogEntry::Record(rec)]);
    lc.logger(Some("it-logger"), attrs! { "component" => "facade" })
        .info("logger run-1", attrs! { "k" => "v" });
    lc.flush(None).await.unwrap();
    let st = lc.stats();
    assert_eq!((st.sent, st.failed, st.dropped), (3, 0, 0));

    let reqs = requests(&server, "/api/v2/logs").await;
    assert_eq!(reqs.len(), 1);
    let r = &reqs[0];
    assert_eq!(r.headers["dd-api-key"], API_KEY);
    assert_eq!(r.headers["content-encoding"], "gzip");
    assert_eq!(r.headers["content-type"], "application/json");
    assert!(r.headers.get("dd-application-key").is_none());

    let logs = sent_logs(&server).await;
    let input = find(&logs, "input");
    assert_eq!(input["status"], "warn");
    assert_eq!(input["service"], "cfg-svc");
    assert_eq!(input["hostname"], "rust-host");
    assert_eq!(input["ddsource"], "grow-sdk");
    assert_eq!(
        input["ddtags"],
        "env:it,version:1.2.3,team:sdk,lang:rust,bad_tag_x"
    );
    assert_eq!(input["attr.service"], "user-value");
    assert_eq!(input["password"], "[REDACTED]");
    assert_eq!(input["user"], json!({"id": 42}));
    assert!(input["timestamp"].is_i64());
    assert!(
        input.get("team.owner").is_none(),
        "other resource keys are not sent"
    );
    let record = find(&logs, "record");
    assert_eq!(record["status"], "error");
    assert_eq!(record["service"], "checkout");
    assert_eq!(
        record["ddtags"],
        "env:test,version:1.2.3,team:sdk,lang:rust,bad_tag_x"
    );
    assert_eq!(record["dd.trace_id"], "11803532876627986230");
    assert_eq!(record["dd.span_id"], "67667974448284343");
    assert_eq!(record["otel.trace_id"], "4bf92f3577b34da6a3ce929d0e0e4736");
    assert_eq!(record["otel.span_id"], "00f067aa0ba902b7");
    assert!(record["message"]
        .as_str()
        .unwrap()
        .contains("Grüße – 日本語 \"quoted\"\u{2028}"));
    let logger = find(&logs, "logger");
    assert_eq!(logger["logger.name"], "it-logger");
    assert_eq!(logger["component"], "facade");
    assert_eq!(logger["status"], "info");

    // close flushes what is queued; later log clients are refused
    lc.send(LogEntry::Input(LogInput::new("closing run-1")));
    c.close().await;
    assert!(sent_logs(&server)
        .await
        .iter()
        .any(|l| l["message"] == "closing run-1"));
    assert_eq!(
        c.log_client(LogClientOptions::default())
            .unwrap_err()
            .code(),
        ErrorCode::Cancelled
    );
}

#[tokio::test]
async fn exporter_plain_and_defaults() {
    let server = intake(200).await;
    let c = client(&server);
    let exp = c.exporter();
    assert_eq!(exp.defaults().source.as_deref(), Some("grow-sdk"));
    assert!(format!("{exp:?}").contains("DatadogLogExporter"));
    let res = exp
        .export(
            &[LogRecord::new(Severity::Info, "plain")],
            ExportOptions::new(false),
        )
        .await
        .unwrap();
    assert_eq!((res.accepted, res.rejected), (1, 0));
    let reqs = requests(&server, "/api/v2/logs").await;
    assert!(reqs[0].headers.get("content-encoding").is_none());
    let logs = sent_logs(&server).await;
    assert_eq!(logs[0]["status"], "info");
    assert!(logs[0].get("service").is_none());
    assert!(logs[0].get("ddtags").is_none());
    let empty = exp.export(&[], ExportOptions::default()).await.unwrap();
    assert_eq!(empty.accepted, 0);
    exp.shutdown().await.unwrap();
}

#[tokio::test]
async fn splits_at_entry_and_byte_limits() {
    let server = intake(202).await;
    let exp = client(&server).exporter();
    let recs: Vec<LogRecord> = (0..2500)
        .map(|i| LogRecord::new(Severity::Info, format!("entry {i}")))
        .collect();
    let res = exp.export(&recs, ExportOptions::default()).await.unwrap();
    assert_eq!(res.accepted, 2500);
    let reqs = requests(&server, "/api/v2/logs").await;
    let sizes: Vec<usize> = reqs
        .iter()
        .map(|r| body_json(r).as_array().unwrap().len())
        .collect();
    assert_eq!(sizes, vec![1000, 1000, 500]);

    let big = "x".repeat(900_000);
    let recs: Vec<LogRecord> = (0..12)
        .map(|i| LogRecord::new(Severity::Info, format!("big {i} {big}")))
        .collect();
    let encoded = exp.encode(&recs);
    assert!(encoded.payloads.len() >= 3);
    assert_eq!(
        encoded.payloads.iter().map(|p| p.entries).sum::<usize>(),
        12
    );
    for p in &encoded.payloads {
        assert!(p.body.len() <= MAX_PAYLOAD_BYTES, "{}", p.body.len());
        let v: Value = serde_json::from_str(&p.body).unwrap();
        assert_eq!(v.as_array().unwrap().len(), p.entries);
    }
}

#[tokio::test]
async fn truncates_and_rejects_oversized_logs() {
    let server = intake(202).await;
    let exp = client(&server).exporter();
    let huge = LogRecord::new(Severity::Info, format!("huge {}", "y\"é".repeat(400_000)));
    let encoded = exp.encode(std::slice::from_ref(&huge));
    assert_eq!(encoded.truncated, 1);
    let body = &encoded.payloads[0].body;
    assert!(body.len() - 2 <= MAX_LOG_BYTES);
    assert!(body.len() - 2 > MAX_LOG_BYTES - 10, "{}", body.len());
    let v: Value = serde_json::from_str(body).unwrap();
    assert_eq!(v[0][TRUNCATED_ATTRIBUTE], true);
    assert!(v[0]["message"].as_str().unwrap().starts_with("huge "));

    let meta = LogRecord::new(Severity::Info, "meta").with_attribute("blob", "z".repeat(1_200_000));
    let ok = LogRecord::new(Severity::Info, "ok");
    let res = exp
        .export(&[huge, meta, ok], ExportOptions::default())
        .await
        .unwrap();
    assert_eq!((res.accepted, res.rejected), (2, 1));
    assert_eq!(res.error.unwrap().code(), ErrorCode::Validation);
}

#[tokio::test]
async fn intake_errors_and_retries() {
    let cases = [
        ("send.400_injected", ErrorCode::Validation, 1),
        ("send.401_injected", ErrorCode::Authentication, 1),
        ("send.403_invalid_key", ErrorCode::Authentication, 1),
        ("send.413_injected", ErrorCode::Validation, 1),
        ("send.408_injected", ErrorCode::Timeout, 2),
        ("send.429_injected", ErrorCode::RateLimited, 2),
        ("send.500_injected", ErrorCode::Unavailable, 2),
        ("send.503_injected", ErrorCode::Unavailable, 2),
    ];
    for (fixture, code, attempts) in cases {
        let server = MockServer::start().await;
        Mock::given(path("/api/v2/logs"))
            .respond_with(respond(fixture))
            .mount(&server)
            .await;
        let c =
            DatadogLogsClient::new(builder(&server).retry(fast_retry(1)).build().unwrap()).unwrap();
        let err = c
            .exporter()
            .export(
                &[LogRecord::new(Severity::Info, "fault")],
                ExportOptions::default(),
            )
            .await
            .unwrap_err();
        assert_eq!(err.code(), code, "{fixture}: {err}");
        assert_eq!(
            requests(&server, "/api/v2/logs").await.len(),
            attempts,
            "{fixture}"
        );
        assert!(!format!("{err:?}").contains(API_KEY));
    }
}

#[tokio::test]
async fn partial_failure_after_first_request() {
    let server = MockServer::start().await;
    let calls = Arc::new(Mutex::new(0));
    let counter = calls.clone();
    Mock::given(path("/api/v2/logs"))
        .and(header("dd-api-key", API_KEY))
        .respond_with(move |_: &Request| {
            let mut n = counter.lock().unwrap();
            *n += 1;
            if *n == 1 {
                ResponseTemplate::new(202).set_body_json(json!({}))
            } else {
                ResponseTemplate::new(400).set_body_json(json!({"errors": ["Bad Request"]}))
            }
        })
        .mount(&server)
        .await;
    let exp = client(&server).exporter();
    let recs: Vec<LogRecord> = (0..1500)
        .map(|i| LogRecord::new(Severity::Info, format!("p {i}")))
        .collect();
    let res = exp.export(&recs, ExportOptions::default()).await.unwrap();
    assert_eq!((res.accepted, res.rejected), (1000, 500));
    assert_eq!(res.error.unwrap().code(), ErrorCode::Validation);
}
