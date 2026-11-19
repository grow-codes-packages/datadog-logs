// Taken over from sdk-kit 0.1.0: blocking client against minimal Datadog-like HTTP answers.
//! Blocking client (feature `blocking`).
#![cfg(feature = "blocking")]

use std::io::{Read, Write};
use std::net::TcpListener;
use std::time::Duration;

use grow_datadog_logs as sdk;
use sdk::core::ErrorCode;
use sdk::logging::{LogRecord, SearchOptions, Severity, WaitOptions};
use sdk::{AggregateLogsOptions, Compute, SearchLogsRequest};

use sdk::blocking::DatadogLogsClient as Client;

use sdk::DatadogLogsConfig as Config;

/// Minimal HTTP server on a thread (no async runtime involved): one response per connection.
fn serve(responses: Vec<String>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for body in responses {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buf = vec![0u8; 1 << 16];
            let _ = stream.read(&mut buf);
            let (status, json) = body.split_once(' ').unwrap();
            let resp = format!(
                "HTTP/1.1 {status} X\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{json}",
                json.len()
            );
            stream.write_all(resp.as_bytes()).unwrap();
        }
    });
    format!("http://{addr}")
}

const EVENT: &str = r#"{"data":[{"id":"l1","attributes":{"timestamp":"2026-11-18T12:00:00.000Z","status":"error","message":"stub run"}}],"meta":{}}"#;

#[test]
fn blocking_client() {
    let ok = r#"200 {"valid":true}"#.to_owned();
    let mut answers = vec![ok.clone(), ok.clone(), ok.clone(), "202 {}".to_owned()];
    for _ in 0..4 {
        answers.push(format!("200 {EVENT}"));
    }
    answers.push(r#"200 {"data":{"buckets":[{"by":{},"computes":{"c0":1}}]}}"#.to_owned());
    for _ in 0..2 {
        answers.push(r#"200 {"indexes":[{"name":"main"}]}"#.to_owned());
    }
    let url = serve(answers);
    let cfg = Config::builder()
        .url(url)
        .api_key("k-1234")
        .app_key("a-1234")
        .max_retries(0)
        .build()
        .unwrap();
    let c = Client::new(cfg).unwrap();
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert!(c.validate_api_key().unwrap());
    assert_eq!(
        c.server_version().unwrap_err().code(),
        ErrorCode::Unsupported
    );
    let res = c
        .export(&[LogRecord::new(Severity::Info, "blocking")], false)
        .unwrap();
    assert_eq!(res.accepted, 1);

    let q = c.query().unwrap();
    let _ = q.async_query();
    let page = q.search_logs(&SearchLogsRequest::new("run")).unwrap();
    assert_eq!(page.events.len(), 1);
    assert_eq!(
        q.iterate_logs(SearchLogsRequest::new("run")).unwrap().len(),
        1
    );
    assert_eq!(
        q.search("run", SearchOptions::default())
            .unwrap()
            .records
            .len(),
        1
    );
    let rec = q
        .wait_for_log(
            "run",
            WaitOptions::default().interval(Duration::from_millis(5)),
        )
        .unwrap();
    assert_eq!(rec.body, "stub run");
    let agg = q
        .aggregate_logs(
            &[Compute::new("count")],
            &[],
            &AggregateLogsOptions::default(),
        )
        .unwrap();
    assert_eq!(agg.buckets[0].number("c0"), Some(1.0));
    assert_eq!(q.list_indexes().unwrap()[0].name, "main");
    assert_eq!(q.list_streams().unwrap(), vec!["main"]);

    assert!(!c.async_client().is_closed());
    assert_eq!(c.block_on(async { 7 }), 7);
    c.close();
    c.close();
    assert_eq!(c.ping().unwrap_err().code(), ErrorCode::Cancelled);
    let _ = Client::from_env();
    let bad = Config::builder()
        .api_key("k")
        .ca_cert_path("/nonexistent")
        .build()
        .unwrap();
    assert_eq!(Client::new(bad).unwrap_err().code(), ErrorCode::Config);
    let no_app = Client::new(Config::builder().api_key("k").build().unwrap()).unwrap();
    assert_eq!(no_app.query().unwrap_err().code(), ErrorCode::Config);
}
