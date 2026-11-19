//! Query API: search_logs, iterate_logs, aggregate_logs, indexes, the LogQueryClient mapping,
//! wait_for_log and the polling tail (recorded fixtures; fakeintake cannot serve them).

mod dd;

use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use dd::{body_json, client, fixture, requests, respond, API_KEY, APP_KEY};
use futures::StreamExt;
use grow_datadog_logs as sdk;
use sdk::core::ErrorCode;
use sdk::logging::{LogQueryClient, LogRecord, SearchOptions, SortOrder, TailOptions, WaitOptions};
use sdk::query::{format_time, DEFAULT_QUERY_POLL_INTERVAL};
use sdk::{
    AggregateLogsOptions, Compute, GroupBy, GroupByHistogram, GroupBySort, LogsSort,
    SearchLogsRequest,
};
use serde_json::{json, Value};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const SEARCH: &str = "/api/v2/logs/events/search";
const AGGREGATE: &str = "/api/v2/logs/analytics/aggregate";
const INDEXES: &str = "/api/v1/logs/config/indexes";

async fn serve(p: &str, template: ResponseTemplate) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(path(p))
        .respond_with(template)
        .mount(&server)
        .await;
    server
}

fn page1_cursor() -> String {
    fixture("searchLogs.page1")["response"]["body"]["meta"]["page"]["after"]
        .as_str()
        .unwrap()
        .to_owned()
}

#[tokio::test]
async fn search_logs_page() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path(SEARCH))
        .and(header("dd-api-key", API_KEY))
        .and(header("dd-application-key", APP_KEY))
        .respond_with(respond("searchLogs.page1"))
        .mount(&server)
        .await;
    let q = client(&server).query().unwrap();
    assert!(format!("{q:?}").contains("DatadogLogsQuery"));
    let req = SearchLogsRequest::new("service:checkout \"run-7f3a\"")
        .range("2026-11-18T12:00:00Z", "2026-11-18T12:15:00Z")
        .indexes(["main"])
        .limit(2)
        .storage_tier("flex");
    let page = q.search_logs(&req).await.unwrap();
    assert_eq!(page.events.len(), 2);
    assert_eq!(page.next_cursor(), Some(page1_cursor().as_str()));
    assert_eq!(page.meta.status.as_deref(), Some("done"));
    assert_eq!(page.meta.elapsed, Some(Duration::from_millis(38)));
    assert!(page.meta.request_id.is_some());
    let e = &page.events[0];
    assert_eq!(e.host.as_deref(), Some("host-a"));
    assert_eq!(e.message, "user signed in run-7f3a #2");
    let ts = e.timestamp.unwrap().duration_since(UNIX_EPOCH).unwrap();
    assert_eq!(ts.as_millis() % 1000, 345);

    let rec = &page.records()[0];
    assert_eq!(rec.severity, sdk::logging::Severity::Info);
    assert_eq!(rec.severity_text, "info");
    assert_eq!(rec.resource["service.name"], "checkout");
    assert_eq!(rec.resource["host.name"], "host-a");
    assert_eq!(rec.resource["deployment.environment"], "test");
    assert_eq!(rec.resource["service.version"], "1.2.3");
    assert_eq!(rec.attributes["user"], json!({"id": 42}));
    assert_eq!(rec.attributes["datadog.log_id"], json!(e.id));
    assert_eq!(rec.attributes["datadog.tags"][0], "env:test");
    assert_eq!(
        rec.trace_id.as_deref(),
        Some("4bf92f3577b34da6a3ce929d0e0e4736")
    );
    assert_eq!(rec.span_id.as_deref(), Some("00f067aa0ba902b7"));

    let sent = body_json(&requests(&server, SEARCH).await[0]);
    assert_eq!(
        sent,
        json!({
            "filter": {"query": "service:checkout \"run-7f3a\"", "from": "2026-11-18T12:00:00Z",
                "to": "2026-11-18T12:15:00Z", "indexes": ["main"], "storage_tier": "flex"},
            "sort": "-timestamp",
            "page": {"limit": 2}
        })
    );
}

#[tokio::test]
async fn search_defaults_partial_results_and_protocol_errors() {
    let server = serve(SEARCH, respond("searchLogs.warnings_partial")).await;
    let q = client(&server).query().unwrap();
    let page = q.search_logs(&SearchLogsRequest::default()).await.unwrap();
    assert_eq!(page.events.len(), 1);
    assert!(!page.meta.warnings.is_empty());
    assert!(page.meta.warnings[0].code.is_some());
    let sent = body_json(&requests(&server, SEARCH).await[0]);
    assert_eq!(sent["filter"]["query"], "*");
    assert_eq!(sent["filter"]["from"], "now-15m");
    assert_eq!(sent["filter"]["to"], "now");
    assert_eq!(sent["filter"]["indexes"], json!(["*"]));
    assert_eq!(sent["filter"]["storage_tier"], "indexes");
    assert_eq!(sent["page"], json!({"limit": 100}));

    let server = serve(SEARCH, respond("searchLogs.timeout_status")).await;
    let page = client(&server)
        .query()
        .unwrap()
        .search_logs(
            &SearchLogsRequest::new("x")
                .sort(LogsSort::TimestampAsc)
                .cursor(""),
        )
        .await
        .unwrap();
    assert!(page.events.is_empty());
    assert_eq!(page.meta.status.as_deref(), Some("timeout"));
    assert_eq!(
        body_json(&requests(&server, SEARCH).await[0])["sort"],
        "timestamp"
    );

    for (template, what) in [
        (respond("searchLogs.fakeintake_default"), "no data array"),
        (
            ResponseTemplate::new(200).set_body_string("not json"),
            "invalid JSON",
        ),
        (
            ResponseTemplate::new(200).set_body_json(json!([1])),
            "no JSON object",
        ),
    ] {
        let server = serve(SEARCH, template).await;
        let err = client(&server)
            .query()
            .unwrap()
            .search_logs(&SearchLogsRequest::default())
            .await
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::Unavailable);
        assert!(!err.is_retryable());
        assert!(err.message().contains(what), "{err}");
    }

    let q = client(&server).query().unwrap();
    for limit in [0, 5001] {
        let err = q
            .search_logs(&SearchLogsRequest::default().limit(limit))
            .await
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation);
    }
}

#[tokio::test]
async fn search_errors() {
    let cases = [
        ("searchLogs.401_missing_keys", ErrorCode::Authentication),
        ("searchLogs.403_invalid_keys", ErrorCode::Authentication),
        ("searchLogs.400_invalid_query", ErrorCode::Validation),
        ("searchLogs.429_rate_limited", ErrorCode::RateLimited),
    ];
    for (name, code) in cases {
        let server = serve(SEARCH, respond(name)).await;
        let err = client(&server)
            .query()
            .unwrap()
            .search_logs(&SearchLogsRequest::new("x"))
            .await
            .unwrap_err();
        assert_eq!(err.code(), code, "{name}: {err}");
        assert!(!format!("{err:?}").contains(APP_KEY));
        if code == ErrorCode::RateLimited {
            // X-RateLimit-Reset (1820 s) is the hint; it is longer than max_retry_after
            assert_eq!(err.retry_after(), Some(Duration::from_secs(1820)));
            assert_eq!(requests(&server, SEARCH).await.len(), 1);
        }
    }
}

fn paging_server_responder(req: &Request) -> ResponseTemplate {
    let body: Value = serde_json::from_slice(&req.body).unwrap();
    if body["page"]["cursor"].is_string() {
        respond("searchLogs.page2_last")
    } else {
        respond("searchLogs.page1")
    }
}

#[tokio::test]
async fn iterate_logs_follows_cursor() {
    let server = MockServer::start().await;
    Mock::given(path(SEARCH))
        .respond_with(paging_server_responder)
        .mount(&server)
        .await;
    let q = client(&server).query().unwrap();
    let events: Vec<_> = q
        .iterate_logs(SearchLogsRequest::new("x").page_size(2))
        .collect()
        .await;
    assert_eq!(events.len(), 3);
    assert!(events.iter().all(Result::is_ok));
    let reqs = requests(&server, SEARCH).await;
    assert_eq!(reqs.len(), 2);
    assert_eq!(body_json(&reqs[1])["page"]["cursor"], page1_cursor());
    assert_eq!(body_json(&reqs[1])["page"]["limit"], 2);

    // limit caps the total (and the page size)
    let limited: Vec<_> = q
        .iterate_logs(SearchLogsRequest::new("x").limit(1))
        .collect()
        .await;
    assert_eq!(limited.len(), 1);
    assert_eq!(
        body_json(requests(&server, SEARCH).await.last().unwrap())["page"]["limit"],
        1
    );

    // blocking-style collect through the default page size
    let all: Vec<_> = q.iterate_logs(SearchLogsRequest::new("x")).collect().await;
    assert_eq!(all.len(), 3);

    for bad in [
        SearchLogsRequest::new("x").limit(0),
        SearchLogsRequest::new("x").page_size(0),
        SearchLogsRequest::new("x").page_size(6000),
    ] {
        let out: Vec<_> = q.iterate_logs(bad).collect().await;
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].as_ref().unwrap_err().code(), ErrorCode::Validation);
    }
}

#[tokio::test]
async fn iterate_logs_loop_guard_and_errors() {
    let server = serve(SEARCH, respond("searchLogs.page1")).await;
    let q = client(&server).query().unwrap();
    let out: Vec<_> = q.iterate_logs(SearchLogsRequest::new("x")).collect().await;
    assert_eq!(out.len(), 5, "{out:?}");
    let err = out.last().unwrap().as_ref().unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
    assert!(err.message().contains("repeated a page cursor"));

    let server = serve(SEARCH, respond("searchLogs.401_missing_keys")).await;
    let out: Vec<_> = client(&server)
        .query()
        .unwrap()
        .iterate_logs(SearchLogsRequest::new("x"))
        .collect()
        .await;
    assert_eq!(out.len(), 1);
    assert_eq!(
        out[0].as_ref().unwrap_err().code(),
        ErrorCode::Authentication
    );
}

#[tokio::test]
async fn aggregate_logs() {
    let server = serve(AGGREGATE, respond("aggregateLogs.count_by_service")).await;
    let q = client(&server).query().unwrap();
    let res = q
        .aggregate_logs(
            &[Compute::new("count")],
            &[GroupBy::new("service").limit(5)],
            &AggregateLogsOptions::new("env:test").range("now-1h", "now"),
        )
        .await
        .unwrap();
    assert_eq!(res.buckets.len(), 1);
    assert_eq!(res.buckets[0].by["service"], "checkout");
    assert_eq!(res.buckets[0].number("c0"), Some(3.0));
    assert_eq!(res.buckets[0].number("c9"), None);
    assert!(res.buckets[0].timeseries("c0").is_none());
    assert_eq!(res.meta.status.as_deref(), Some("done"));
    let sent = body_json(&requests(&server, AGGREGATE).await[0]);
    assert_eq!(
        sent,
        json!({
            "compute": [{"aggregation": "count", "type": "total"}],
            "filter": {"query": "env:test", "from": "now-1h", "to": "now", "indexes": ["*"],
                "storage_tier": "indexes"},
            "group_by": [{"facet": "service", "limit": 5}]
        })
    );

    let server = serve(AGGREGATE, respond("aggregateLogs.timeseries")).await;
    let res = client(&server)
        .query()
        .unwrap()
        .aggregate_logs(
            &[Compute {
                kind: String::new(),
                ..Compute::timeseries("count", "5m")
            }],
            &[],
            &AggregateLogsOptions::default(),
        )
        .await
        .unwrap();
    let points = res.buckets[0].timeseries("c0").unwrap();
    assert!(points.len() >= 2);
    assert_eq!(points[0].value, 3.0);
    assert!(points[0].time.is_some());
    let sent = body_json(&requests(&server, AGGREGATE).await[0]);
    assert_eq!(sent["compute"][0]["type"], "total");
    assert_eq!(sent["compute"][0]["interval"], "5m");
    assert!(sent.get("group_by").is_none());

    let server = serve(AGGREGATE, respond("aggregateLogs.paged")).await;
    let group = GroupBy {
        sort: Some(GroupBySort {
            aggregation: Some("count".into()),
            order: Some("desc".into()),
            kind: Some("measure".into()),
            metric: None,
        }),
        missing: Some(json!("n/a")),
        total: Some(json!(true)),
        histogram: Some(GroupByHistogram {
            interval: 10.0,
            min: 0.0,
            max: 100.0,
        }),
        ..GroupBy::new("@user.id")
    };
    let res = client(&server)
        .query()
        .unwrap()
        .aggregate_logs(
            &[Compute::new("avg").metric("@duration")],
            &[group],
            &AggregateLogsOptions {
                cursor: Some("c-1".into()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    assert!(res.meta.next_cursor.is_some());
    let sent = body_json(&requests(&server, AGGREGATE).await[0]);
    assert_eq!(sent["page"], json!({"cursor": "c-1"}));
    assert_eq!(sent["compute"][0]["metric"], "@duration");
    assert_eq!(sent["group_by"][0]["sort"]["type"], "measure");
    assert_eq!(sent["group_by"][0]["histogram"]["max"], 100.0);
}

#[tokio::test]
async fn aggregate_validation_and_errors() {
    let server = serve(AGGREGATE, respond("aggregateLogs.401_missing_keys")).await;
    let q = client(&server).query().unwrap();
    let opts = AggregateLogsOptions::default();
    for (compute, group) in [
        (vec![], vec![]),
        (vec![Compute::new(" ")], vec![]),
        (vec![Compute::new("count")], vec![GroupBy::new("")]),
    ] {
        let err = q.aggregate_logs(&compute, &group, &opts).await.unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation);
    }
    let err = q
        .aggregate_logs(&[Compute::new("count")], &[], &opts)
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);

    let server = serve(
        AGGREGATE,
        ResponseTemplate::new(200).set_body_json(json!({"data": {}})),
    )
    .await;
    let err = client(&server)
        .query()
        .unwrap()
        .aggregate_logs(&[Compute::new("count")], &[], &opts)
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);

    let server = serve(
        AGGREGATE,
        ResponseTemplate::new(200).set_body_json(
            json!({"data": {"buckets": [1, {"computes": {"c0": "7.5", "c1": true}}]}}),
        ),
    )
    .await;
    let res = client(&server)
        .query()
        .unwrap()
        .aggregate_logs(&[Compute::new("count")], &[], &opts)
        .await
        .unwrap();
    assert_eq!(res.buckets.len(), 1);
    assert_eq!(res.buckets[0].number("c0"), Some(7.5));
    assert_eq!(res.buckets[0].number("c1"), None);
    assert!(res.buckets[0].by.is_empty());
}

#[tokio::test]
async fn indexes_streams_labels() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(INDEXES))
        .and(header("dd-application-key", APP_KEY))
        .respond_with(respond("listStreams.indexes"))
        .mount(&server)
        .await;
    let q = client(&server).query().unwrap();
    let ix = q.list_indexes().await.unwrap();
    assert_eq!(ix.len(), 1);
    assert_eq!(ix[0].name, "main");
    assert_eq!(ix[0].query.as_deref(), Some("*"));
    assert_eq!(ix[0].retention_days, Some(15));
    assert_eq!(ix[0].daily_limit, Some(300_000_000));
    assert_eq!(ix[0].is_rate_limited, Some(false));
    assert_eq!(q.list_streams().await.unwrap(), vec!["main"]);
    assert_eq!(
        q.list_labels().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );

    let server = serve(INDEXES, ResponseTemplate::new(200).set_body_json(json!({}))).await;
    let q = client(&server).query().unwrap();
    assert_eq!(
        q.list_indexes().await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
    assert_eq!(
        q.list_streams().await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
}

#[tokio::test]
async fn category_search_and_wait_for_log() {
    let server = serve(SEARCH, respond("searchLogs.page1")).await;
    let q = client(&server).query().unwrap();
    let start = UNIX_EPOCH + Duration::from_secs(1_795_000_000);
    let res = q
        .search(
            "run-7f3a",
            SearchOptions::default()
                .range(Some(start), Some(start + Duration::from_secs(60)))
                .order(SortOrder::Asc)
                .limit(10)
                .cursor("abc"),
        )
        .await
        .unwrap();
    assert_eq!(res.records.len(), 2);
    assert!(res.next_cursor.is_some());
    assert!(res.total.is_none());
    let sent = body_json(&requests(&server, SEARCH).await[0]);
    assert_eq!(sent["filter"]["from"], format_time(start));
    assert_eq!(sent["sort"], "timestamp");
    assert_eq!(sent["page"], json!({"limit": 10, "cursor": "abc"}));

    let rec = q
        .wait_for_log(
            "run-7f3a",
            WaitOptions::default()
                .interval(Duration::from_millis(10))
                .predicate(|r: &LogRecord| r.body.ends_with("#1")),
        )
        .await
        .unwrap();
    assert_eq!(rec.body, "user signed in run-7f3a #1");

    let server = serve(SEARCH, respond("searchLogs.empty")).await;
    let err = client(&server)
        .query()
        .unwrap()
        .wait_for_log(
            "nothing",
            WaitOptions::default()
                .timeout(Duration::from_millis(50))
                .interval(Duration::from_millis(10)),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Timeout);
}

fn tail_event(id: &str, msg: &str, ts: SystemTime) -> Value {
    json!({"id": id, "type": "log", "attributes": {"timestamp": format_time(ts), "status": "warn",
        "message": msg, "tags": [], "attributes": {"dd": {"trace_id": "12345", "span_id": 99}}}})
}

#[tokio::test]
async fn polling_tail() {
    let server = MockServer::start().await;
    let now = SystemTime::now();
    let calls = Arc::new(Mutex::new(0u32));
    let counter = calls.clone();
    Mock::given(path(SEARCH))
        .respond_with(move |_: &Request| {
            let mut n = counter.lock().unwrap();
            *n += 1;
            match *n {
                1 => ResponseTemplate::new(200).set_body_json(json!({"data": [tail_event("a", "one", now)], "meta": {}})),
                2 => ResponseTemplate::new(503).set_body_json(json!({"errors": ["busy"]})),
                _ => ResponseTemplate::new(200).set_body_json(json!({"data": [
                    tail_event("a", "one", now), tail_event("b", "two", now + Duration::from_millis(5))], "meta": {}})),
            }
        })
        .mount(&server)
        .await;
    let c = dd::client(&server);
    let c = sdk::DatadogLogsClient::new(
        dd::builder(&server)
            .retry(dd::fast_retry(0))
            .build()
            .unwrap(),
    )
    .unwrap_or(c);
    let q = c.query().unwrap();
    let got: Arc<Mutex<Vec<LogRecord>>> = Arc::default();
    let sink = got.clone();
    let handle = q
        .tail(
            "run",
            Arc::new(move |r| {
                if r.body == "two" {
                    sink.lock().unwrap().push(r.clone());
                    panic!("callback panics are contained");
                }
                sink.lock().unwrap().push(r)
            }),
            TailOptions {
                interval: Duration::from_millis(20),
                start: Some(now - Duration::from_secs(5)),
            },
        )
        .await
        .unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while got.lock().unwrap().len() < 2 && std::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let bodies: Vec<String> = got.lock().unwrap().iter().map(|r| r.body.clone()).collect();
    assert_eq!(bodies, vec!["one", "two"]);
    let rec = got.lock().unwrap()[0].clone();
    assert_eq!(
        rec.trace_id.as_deref(),
        Some("00000000000000000000000000003039")
    );
    assert_eq!(rec.span_id.as_deref(), Some("0000000000000063"));
    let sent = body_json(&requests(&server, SEARCH).await[0]);
    assert_eq!(sent["sort"], "timestamp");
    // close stops the tail
    c.close().await;
    tokio::time::sleep(Duration::from_millis(30)).await;
    assert!(handle.is_stopped());
    let err = q
        .tail("x", Arc::new(|_| {}), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn tail_stops_on_fatal_error_and_on_stop() {
    let server = serve(SEARCH, respond("searchLogs.403_invalid_keys")).await;
    let q = client(&server).query().unwrap();
    let handle = q
        .tail(
            "x",
            Arc::new(|_| {}),
            TailOptions {
                interval: Duration::ZERO,
                start: None,
            },
        )
        .await
        .unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while !handle.is_stopped() && std::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert!(handle.is_stopped());

    let server = serve(SEARCH, respond("searchLogs.empty")).await;
    let q = client(&server).query().unwrap();
    let handle = q
        .tail("x", Arc::new(|_| {}), TailOptions::default())
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(50)).await;
    handle.stop();
    assert!(handle.is_stopped());
    assert_eq!(DEFAULT_QUERY_POLL_INTERVAL, Duration::from_secs(5));
}
