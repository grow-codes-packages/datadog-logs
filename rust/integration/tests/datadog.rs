// Owned by this repository.
//! Integration suite against datadog/fakeintake (Docker): every public function is called once.
//! Intake functions are real round trips (send → read the captured payload back). The query
//! API cannot be served by fakeintake: those calls run against wire stubs installed through the
//! fakeintake override (request plumbing is asserted on the captured request), not round trips.

use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use futures::StreamExt;
use grow_datadog_logs as sdk;
use grow_datadog_logs_integration::{assert_code, run_id, setup, FakeIntake};
use sdk::logging::{
    attrs, BatchOptions, ExportOptions, LogBridge, LogClient, LogEntry, LogExporter, LogInput,
    LogLayer, LogQueryClient, LogRecord, SearchOptions, Severity, TailOptions, WaitOptions,
};
use sdk::mapping::{
    MAX_ENTRIES_PER_REQUEST, MAX_LOG_BYTES, MAX_PAYLOAD_BYTES, TRUNCATED_ATTRIBUTE,
};
use sdk::{
    resolve_site, AggregateLogsOptions, Compute, DatadogLogsClient as Client,
    DatadogLogsConfig as Config, ErrorCode, GroupBy, LogClientOptions, SearchLogsRequest,
};
use serde_json::{json, Value};
use tracing_subscriber::layer::SubscriberExt;

fn client() -> Client {
    Client::from_env().expect("client from env")
}

fn s(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

#[tokio::test]
async fn core() {
    if !setup() {
        return;
    }
    let fi = FakeIntake::new();
    let c = client();
    // the URL is normalized (default port dropped, no trailing slash)
    let env_url = std::env::var("GROW_DATADOG_LOGS_URL").unwrap();
    let ep = c.endpoints();
    assert!(env_url.starts_with(&ep.intake_url), "{ep:?} vs {env_url}");
    assert_eq!(ep.intake_url, ep.api_url);
    assert_eq!(
        resolve_site(c.config().site()).unwrap().api_url,
        "https://api.datadoghq.com"
    );

    let h = c.health().await.unwrap();
    assert!(
        h.healthy && h.status == "ok" && h.details["apiKeyValid"] == "true",
        "{h:?}"
    );
    assert!(c.ping().await.unwrap() > Duration::ZERO);
    assert!(c.validate_api_key().await.unwrap());
    assert_code(c.server_version().await, ErrorCode::Unsupported);

    // rejected key: the container answers 403 like Datadog does for a missing or invalid key
    fi.override_response(
        "GET",
        "/api/v1/validate",
        403,
        r#"{"errors":["Forbidden"]}"#,
    )
    .await;
    let rejected = c.validate_api_key().await;
    let ping = c.ping().await;
    let health = c.health().await;
    fi.override_response("GET", "/api/v1/validate", 200, r#"{"valid":true}"#)
        .await;
    assert!(!rejected.unwrap());
    let err = assert_code(ping, ErrorCode::Authentication);
    assert_eq!(err.http_status(), Some(403));
    let health = health.unwrap();
    assert!(
        !health.healthy && health.status == "unauthorized",
        "{health:?}"
    );

    c.close().await;
    c.close().await;
    assert!(c.is_closed());
}

#[tokio::test]
async fn ingest_round_trip() {
    if !setup() {
        return;
    }
    let fi = FakeIntake::new();
    let run = run_id();
    let c = Client::new(
        Config::builder()
            .service("cfg-svc")
            .env("it")
            .version("1.2.3")
            .hostname("rust-host")
            .tags(["team:sdk", "lang:rust"])
            .build_from_env()
            .unwrap(),
    )
    .unwrap();
    let lc = c
        .log_client(
            LogClientOptions::default()
                .batch(BatchOptions::default().flush_interval(Duration::from_secs(3600))),
        )
        .unwrap();
    lc.send(LogEntry::Input(
        LogInput::new(format!("input {run}"))
            .severity(Severity::Warn)
            .attribute("run", run.clone())
            .attribute("service", "user-value")
            .attribute("password", "hunter2")
            .attribute("user", json!({"id": 42})),
    ));
    let mut rec = LogRecord::new(
        Severity::Error,
        format!("record {run} Grüße – 日本語 \"quoted\""),
    )
    .with_trace("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
    rec.timestamp = SystemTime::now() - Duration::from_secs(60);
    rec.resource
        .insert("service.name".into(), "checkout".into());
    rec.resource
        .insert("deployment.environment".into(), "test".into());
    lc.send_batch(vec![LogEntry::Record(rec)]);
    lc.logger(Some("it-logger"), attrs! { "component" => "facade" })
        .info(format!("logger {run}"), attrs! { "k" => "v" });

    // framework adapters: tracing Layer and log::Log
    let shared: Arc<dyn LogClient> = Arc::new(lc.clone());
    let subscriber = tracing_subscriber::registry().with(LogLayer::new(shared.clone()));
    tracing::subscriber::with_default(subscriber, || {
        tracing::error!(attempt = 3, "tracing {}", run);
    });
    let bridge = LogBridge::new(shared);
    log::Log::log(
        &bridge,
        &log::Record::builder()
            .args(format_args!("bridge {run}"))
            .level(log::Level::Warn)
            .target("it")
            .build(),
    );
    log::Log::flush(&bridge);

    lc.flush(Some(Duration::from_secs(30))).await.unwrap();
    let st = lc.stats();
    assert_eq!((st.sent, st.failed, st.dropped), (5, 0, 0), "{st:?}");

    let (logs, _) = fi.logs_with(&run).await;
    assert_eq!(logs.len(), 5, "{logs:?}");
    let find = |prefix: &str| {
        logs.iter()
            .find(|l| s(&l["message"]).starts_with(prefix))
            .unwrap_or_else(|| panic!("no {prefix} log in {logs:?}"))
            .clone()
    };
    let input = find("input");
    for (k, want) in [
        ("status", "warn"),
        ("service", "cfg-svc"),
        ("hostname", "rust-host"),
        ("ddsource", "grow-sdk"),
        ("ddtags", "env:it,version:1.2.3,team:sdk,lang:rust"),
        ("attr.service", "user-value"),
        ("password", "[REDACTED]"),
        ("user", r#"{"id":42}"#),
    ] {
        assert_eq!(s(&input[k]), want, "input {k}");
    }
    assert!(input["timestamp"].is_u64());
    let record = find("record");
    for (k, want) in [
        ("status", "error"),
        ("service", "checkout"),
        ("ddtags", "env:test,version:1.2.3,team:sdk,lang:rust"),
        ("dd.trace_id", "11803532876627986230"),
        ("dd.span_id", "67667974448284343"),
        ("otel.trace_id", "4bf92f3577b34da6a3ce929d0e0e4736"),
        ("otel.span_id", "00f067aa0ba902b7"),
    ] {
        assert_eq!(s(&record[k]), want, "record {k}");
    }
    assert!(s(&record["message"]).contains("Grüße – 日本語 \"quoted\""));
    let logger = find("logger");
    assert_eq!(s(&logger["logger.name"]), "it-logger");
    assert_eq!(s(&logger["component"]), "facade");
    assert_eq!(s(&find("tracing")["status"]), "error");
    assert_eq!(s(&find("bridge")["status"]), "warn");

    // exporter without gzip, encode and send_payload
    let marker = run_id();
    let exp = c.exporter();
    let res = exp
        .export(
            &[LogRecord::new(Severity::Info, format!("plain {marker}"))],
            ExportOptions::new(false),
        )
        .await
        .unwrap();
    assert_eq!(res.accepted, 1);
    let encoded = exp.encode(&[LogRecord::new(Severity::Debug, format!("raw {marker}"))]);
    exp.send_payload(&encoded.payloads[0].body, true)
        .await
        .unwrap();
    exp.shutdown().await.unwrap();
    let (logs, _) = fi.logs_with(&marker).await;
    assert_eq!(logs.len(), 2, "{logs:?}");

    // close flushes
    let marker = run_id();
    let c2 = client();
    let lc2 = c2
        .log_client(
            LogClientOptions::default()
                .batch(BatchOptions::default().flush_interval(Duration::from_secs(3600))),
        )
        .unwrap();
    lc2.send(LogEntry::Input(LogInput::new(format!("closing {marker}"))));
    c2.close().await;
    assert_eq!(fi.logs_with(&marker).await.0.len(), 1, "close must flush");
    lc.close().await.unwrap();
    c.close().await;
}

#[tokio::test]
async fn intake_limits() {
    if !setup() {
        return;
    }
    let fi = FakeIntake::new();
    let exp = client().exporter();

    let marker = run_id();
    let recs: Vec<LogRecord> = (0..2500)
        .map(|_| LogRecord::new(Severity::Info, format!("entry {marker}")))
        .collect();
    assert_eq!(
        exp.export(&recs, ExportOptions::default())
            .await
            .unwrap()
            .accepted,
        2500
    );
    let (logs, payloads) = fi.logs_with(&marker).await;
    assert_eq!((logs.len(), payloads.len()), (2500, 3));
    assert!(payloads
        .iter()
        .all(|p| p.logs.len() <= MAX_ENTRIES_PER_REQUEST));

    let marker = run_id();
    let big = "x".repeat(900_000);
    let recs: Vec<LogRecord> = (0..12)
        .map(|_| LogRecord::new(Severity::Info, format!("big {marker} {big}")))
        .collect();
    assert_eq!(
        exp.export(&recs, ExportOptions::default())
            .await
            .unwrap()
            .accepted,
        12
    );
    let (logs, payloads) = fi.logs_with(&marker).await;
    assert_eq!(logs.len(), 12);
    assert!(payloads.len() >= 3);
    assert!(payloads.iter().all(|p| p.raw.len() <= MAX_PAYLOAD_BYTES));

    let marker = run_id();
    let huge = LogRecord::new(
        Severity::Info,
        format!("huge {marker} {}", "y".repeat(1_100_000)),
    );
    assert_eq!(
        exp.export(&[huge], ExportOptions::default())
            .await
            .unwrap()
            .accepted,
        1
    );
    let (logs, payloads) = fi.logs_with(&marker).await;
    assert_eq!(logs.len(), 1);
    assert_eq!(logs[0][TRUNCATED_ATTRIBUTE], true);
    assert!(payloads[0].raw.len() - 2 <= MAX_LOG_BYTES);
}

#[tokio::test]
async fn intake_errors() {
    if !setup() {
        return;
    }
    let fi = FakeIntake::new();
    let c = Client::new(
        Config::builder()
            .retry(sdk::RetryPolicy {
                max_retries: 1,
                initial_backoff: Duration::from_millis(1),
                ..sdk::RetryPolicy::default()
            })
            .build_from_env()
            .unwrap(),
    )
    .unwrap();
    let cases = [
        (
            400,
            r#"{"errors":["Bad Request"]}"#,
            ErrorCode::Validation,
            1,
        ),
        (
            403,
            r#"{"errors":["Forbidden","API key is missing or invalid"]}"#,
            ErrorCode::Authentication,
            1,
        ),
        (
            413,
            r#"{"errors":["Payload too large"]}"#,
            ErrorCode::Validation,
            1,
        ),
        (
            429,
            r#"{"errors":["Too many requests"]}"#,
            ErrorCode::RateLimited,
            2,
        ),
        (
            500,
            r#"{"errors":["Internal Server Error"]}"#,
            ErrorCode::Unavailable,
            2,
        ),
        (
            503,
            r#"{"errors":["Service Unavailable"]}"#,
            ErrorCode::Unavailable,
            2,
        ),
    ];
    let mut failures = Vec::new();
    for (status, body, code, attempts) in cases {
        fi.override_response("POST", "/api/v2/logs", status, body)
            .await;
        let marker = run_id();
        let res = c
            .exporter()
            .export(
                &[LogRecord::new(Severity::Info, format!("fault {marker}"))],
                ExportOptions::default(),
            )
            .await;
        let captured = fi.logs_with(&marker).await.0.len();
        match res {
            Err(e)
                if e.code() == code && e.http_status() == Some(status) && captured == attempts => {}
            other => failures.push(format!("{status}: {other:?}, {captured} attempts")),
        }
    }
    fi.override_response("POST", "/api/v2/logs", 202, "{}")
        .await;
    assert!(failures.is_empty(), "{failures:?}");
}

/// A Datadog search answer served by the container (wire stub, not a round trip).
fn search_stub(run: &str) -> String {
    let ts = sdk::query::format_time(SystemTime::now());
    json!({
        "data": [{"id": "stub-1", "type": "log", "attributes": {
            "timestamp": ts, "host": "rust-host", "service": "checkout", "status": "error",
            "message": format!("stub {run}"), "tags": ["env:it", "version:1.2.3"],
            "attributes": {"dd": {"trace_id": "11803532876627986230", "span_id": "67667974448284343"}, "run": run}
        }}],
        "meta": {"elapsed": 5, "request_id": format!("req-{run}"), "status": "done"}
    })
    .to_string()
}

const AGGREGATE_STUB: &str = r#"{"data":{"buckets":[{"by":{"service":"checkout"},"computes":{"c0":3}}]},"meta":{"elapsed":7,"request_id":"agg","status":"done"}}"#;
const INDEXES_STUB: &str = r#"{"indexes":[{"name":"main","filter":{"query":"*"},"num_retention_days":15,"daily_limit":300000000,"is_rate_limited":false,"exclusion_filters":[]}]}"#;

async fn install_query_stubs(fi: &FakeIntake, run: &str) {
    fi.override_response("POST", "/api/v2/logs/events/search", 200, &search_stub(run))
        .await;
    fi.override_response(
        "POST",
        "/api/v2/logs/analytics/aggregate",
        200,
        AGGREGATE_STUB,
    )
    .await;
    fi.override_response("GET", "/api/v1/logs/config/indexes", 200, INDEXES_STUB)
        .await;
}

#[tokio::test]
async fn query_wire_stubs() {
    if !setup() {
        return;
    }
    let fi = FakeIntake::new();
    let run = run_id();
    install_query_stubs(&fi, &run).await;
    let c = client();
    let q = c.query().unwrap();

    let query = format!("service:checkout \"{run}\"");
    let page = q
        .search_logs(
            &SearchLogsRequest::new(query.clone())
                .from("now-1h")
                .limit(50)
                .indexes(["main"]),
        )
        .await
        .unwrap();
    assert_eq!(page.events.len(), 1);
    assert_eq!(
        page.meta.request_id.as_deref(),
        Some(format!("req-{run}").as_str())
    );
    assert!(page.next_cursor().is_none());
    let rec = &page.records()[0];
    assert_eq!(rec.severity, Severity::Error);
    assert_eq!(
        rec.trace_id.as_deref(),
        Some("0000000000000000a3ce929d0e0e4736")
    );
    assert_eq!(rec.span_id.as_deref(), Some("00f067aa0ba902b7"));
    assert_eq!(rec.resource["service.version"], "1.2.3");
    // the search request itself is captured by the container: assert what the SDK sent
    let sent = fi.payloads("/api/v2/logs/events/search").await.unwrap();
    let body: Value = serde_json::from_slice(&sent.last().unwrap().raw).unwrap();
    assert_eq!(body["filter"]["query"], query);
    assert_eq!(body["filter"]["from"], "now-1h");
    assert_eq!(body["filter"]["to"], "now");
    assert_eq!(body["filter"]["indexes"], json!(["main"]));
    assert_eq!(body["sort"], "-timestamp");
    assert_eq!(body["page"]["limit"], 50);

    let events: Vec<_> = q
        .iterate_logs(SearchLogsRequest::new(run.clone()).limit(10))
        .collect()
        .await;
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].as_ref().unwrap().id, "stub-1");
    let res = q
        .search(
            &run,
            SearchOptions::default()
                .range(Some(SystemTime::now() - Duration::from_secs(3600)), None),
        )
        .await
        .unwrap();
    assert_eq!(res.records.len(), 1);
    let found = q
        .wait_for_log(
            &run,
            WaitOptions::default().timeout(Duration::from_secs(10)),
        )
        .await
        .unwrap();
    assert_eq!(found.body, format!("stub {run}"));

    let agg = q
        .aggregate_logs(
            &[Compute::new("count")],
            &[GroupBy::new("service")],
            &AggregateLogsOptions::new(run.clone()),
        )
        .await
        .unwrap();
    assert_eq!(agg.buckets[0].number("c0"), Some(3.0));
    assert_eq!(agg.buckets[0].by["service"], "checkout");
    let ix = q.list_indexes().await.unwrap();
    assert_eq!(
        (ix[0].name.as_str(), ix[0].retention_days),
        ("main", Some(15))
    );
    assert_eq!(q.list_streams().await.unwrap(), vec!["main"]);
    assert_code(q.list_labels().await, ErrorCode::Unsupported);

    let got: Arc<Mutex<Vec<LogRecord>>> = Arc::default();
    let sink = got.clone();
    let handle = q
        .tail(
            &run,
            Arc::new(move |r| sink.lock().unwrap().push(r)),
            TailOptions {
                interval: Duration::from_millis(100),
                start: Some(SystemTime::now() - Duration::from_secs(3600)),
            },
        )
        .await
        .unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while got.lock().unwrap().is_empty() && std::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    tokio::time::sleep(Duration::from_millis(300)).await;
    handle.stop();
    assert_eq!(
        got.lock().unwrap().len(),
        1,
        "each log id is delivered once"
    );
    c.close().await;
}

#[test]
fn blocking_client() {
    if !setup() {
        return;
    }
    let rt = tokio::runtime::Runtime::new().unwrap();
    let fi = FakeIntake::new();
    let run = run_id();
    rt.block_on(install_query_stubs(&fi, &run));

    let c = sdk::blocking::DatadogLogsClient::from_env().unwrap();
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert!(c.validate_api_key().unwrap());
    assert_eq!(
        c.server_version().unwrap_err().code(),
        ErrorCode::Unsupported
    );
    let res = c
        .export(
            &[LogRecord::new(Severity::Info, format!("blocking {run}"))],
            true,
        )
        .unwrap();
    assert_eq!(res.accepted, 1);
    assert_eq!(
        rt.block_on(fi.logs_with(&format!("blocking {run}")))
            .0
            .len(),
        1
    );

    let q = c.query().unwrap();
    assert_eq!(
        q.search_logs(&SearchLogsRequest::new(run.clone()))
            .unwrap()
            .events
            .len(),
        1
    );
    assert_eq!(
        q.iterate_logs(SearchLogsRequest::new(run.clone()).limit(5))
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        q.search(&run, SearchOptions::default())
            .unwrap()
            .records
            .len(),
        1
    );
    assert_eq!(
        q.wait_for_log(&run, WaitOptions::default()).unwrap().body,
        format!("stub {run}")
    );
    let agg = q
        .aggregate_logs(
            &[Compute::new("count")],
            &[],
            &AggregateLogsOptions::default(),
        )
        .unwrap();
    assert_eq!(agg.buckets.len(), 1);
    assert_eq!(q.list_indexes().unwrap().len(), 1);
    assert_eq!(q.list_streams().unwrap(), vec!["main"]);
    c.close();
}
