//! `LogRecord` ⇄ Datadog JSON mapping (API.md §5.2, §6.1; DECISIONS 6, 7, 9).

use std::time::{Duration, UNIX_EPOCH};

use grow_datadog_logs as sdk;
use sdk::logging::{Attributes, LogRecord, Severity};
use sdk::mapping::*;
use serde_json::{json, Value};

#[test]
fn trace_and_span_ids() {
    assert_eq!(
        trace_id_to_datadog(" 4BF92F3577B34DA6A3CE929D0E0E4736 ").as_deref(),
        Some("11803532876627986230")
    );
    assert_eq!(trace_id_to_datadog("ff").as_deref(), Some("255"));
    assert!(trace_id_to_datadog("").is_none());
    assert!(trace_id_to_datadog("xyz").is_none());
    assert!(trace_id_to_datadog(&"a".repeat(33)).is_none());
    assert_eq!(
        span_id_to_datadog("00f067aa0ba902b7").as_deref(),
        Some("67667974448284343")
    );
    assert!(span_id_to_datadog(&"a".repeat(17)).is_none());
    assert!(span_id_to_datadog("g1").is_none());

    assert_eq!(
        datadog_id_to_hex(&json!("11803532876627986230"), 32).as_deref(),
        Some("0000000000000000a3ce929d0e0e4736")
    );
    assert_eq!(
        datadog_id_to_hex(&json!(255), 16).as_deref(),
        Some("00000000000000ff")
    );
    assert_eq!(
        datadog_id_to_hex(&json!("ABC"), 16).as_deref(),
        Some("0000000000000abc")
    );
    assert!(datadog_id_to_hex(&json!(0), 16).is_none());
    assert!(datadog_id_to_hex(&json!(-1), 16).is_none());
    assert!(datadog_id_to_hex(&json!("0"), 16).is_none());
    assert!(datadog_id_to_hex(&json!("99999999999999999999"), 32).is_none());
    assert!(datadog_id_to_hex(&json!("nothex!"), 16).is_none());
    assert!(datadog_id_to_hex(&json!("a".repeat(17)), 16).is_none());
    assert!(datadog_id_to_hex(&json!(true), 16).is_none());
}

#[test]
fn statuses_and_severities() {
    let mut r = LogRecord::new(Severity::Warn, "x");
    r.severity_text = String::new();
    assert_eq!(datadog_status(&r), "warn");
    r.severity_text = " NOTICE ".into();
    assert_eq!(datadog_status(&r), "notice");
    for (status, sev) in [
        ("emerg", Severity::Fatal),
        ("CRITICAL", Severity::Fatal),
        ("err", Severity::Error),
        ("warning", Severity::Warn),
        ("notice", Severity::Info),
        ("ok", Severity::Info),
        ("debug", Severity::Debug),
        ("trace", Severity::Trace),
        ("weird", Severity::Info),
    ] {
        assert_eq!(severity_from_status(status), sev, "{status}");
    }
}

#[test]
fn ddtags() {
    assert_eq!(
        build_ddtags(
            Some("prod"),
            Some("1.0"),
            &["a:1".into(), "env:prod".into(), "b c".into(), "".into()]
        )
        .as_deref(),
        Some("env:prod,version:1.0,a:1,b_c")
    );
    assert!(build_ddtags(None, None, &[]).is_none());
}

#[test]
fn entry_mapping() {
    let mut r = LogRecord::new(Severity::Info, "hello")
        .with_attribute("status", "user")
        .with_attribute("dd.trace_id", "user-trace")
        .with_attribute("plain", 1);
    r.timestamp = UNIX_EPOCH + Duration::from_millis(1_795_033_887_895);
    r.resource.insert("service.name".into(), json!(42));
    r.resource.insert("host.name".into(), json!(" "));
    r.resource.insert("service.version".into(), Value::Null);
    r.trace_id = Some("4bf92f3577b34da6a3ce929d0e0e4736".into());
    r.span_id = Some("bad".into());
    let defaults = EntryDefaults {
        hostname: Some("default-host".into()),
        version: Some("9".into()),
        ..Default::default()
    };
    let e = to_datadog_entry(&r, &defaults);
    assert_eq!(e["timestamp"], 1_795_033_887_895i64);
    assert_eq!(e["service"], "42");
    assert_eq!(e["hostname"], "default-host");
    assert_eq!(e["ddtags"], "version:9");
    assert_eq!(e["attr.status"], "user");
    assert_eq!(e["attr.dd.trace_id"], "user-trace");
    assert_eq!(e["plain"], 1);
    assert_eq!(e["dd.trace_id"], "11803532876627986230");
    assert!(e.get("ddsource").is_none());
    // the span id must be valid hex: 'bad' is hex, so it is kept
    assert_eq!(e["otel.span_id"], "0000000000000bad");

    r.span_id = Some("zz".into());
    r.trace_id = Some("short1".into());
    let e = to_datadog_entry(&r, &EntryDefaults::default());
    assert!(e.get("dd.span_id").is_none());
    assert!(e.get("dd.trace_id").is_none(), "invalid hex trace id");
    r.trace_id = Some("abc".into());
    let e = to_datadog_entry(&r, &EntryDefaults::default());
    assert_eq!(e["otel.trace_id"], "00000000000000000000000000000abc");
    assert!(e.get("otel.span_id").is_none());

    r.timestamp = UNIX_EPOCH - Duration::from_millis(5);
    assert_eq!(
        to_datadog_entry(&r, &EntryDefaults::default())["timestamp"],
        -5
    );
}

#[test]
fn serialization_limits() {
    let mut entry = serde_json::Map::new();
    entry.insert("message".into(), json!("ab\u{1}c\ncd".repeat(10)));
    let small = serialize_entry(&entry, 1000).unwrap();
    assert!(!small.truncated);
    // control characters take 6 bytes, \n 2: budget cuts exactly before the overflow
    let full_len = small.json.len();
    let t = serialize_entry(&entry, full_len - 1).unwrap();
    assert!(t.truncated);
    assert!(t.json.len() < full_len, "{}", t.json);
    let v: Value = serde_json::from_str(&t.json).unwrap();
    assert_eq!(v[TRUNCATED_ATTRIBUTE], true);
    assert!(serialize_entry(&entry, 10).is_none());
    assert_eq!(RESERVED_PREFIX, "attr.");
    assert!(RESERVED_KEYS.contains(&"ddtags"));
}

#[test]
fn search_events() {
    assert!(LogEvent::from_json(&json!("x")).is_none());
    let bare = LogEvent::from_json(&json!({})).unwrap();
    assert_eq!(bare, LogEvent::default());
    let rec = bare.to_log_record();
    assert_eq!(rec.severity_text, "info");
    assert_eq!(rec.timestamp, UNIX_EPOCH);
    assert!(rec.attributes.is_empty() && rec.resource.is_empty() && rec.trace_id.is_none());

    let e = LogEvent::from_json(&json!({
        "id": "x1",
        "attributes": {
            "timestamp": "2026-11-18T14:01:02.345+02:00",
            "status": "ERROR",
            "message": "m",
            "tags": ["env:a", "env:b", "version:2", 7],
            "attributes": {"otel.trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "otel.span_id": "00f067aa0ba902b7"}
        }
    }))
    .unwrap();
    let utc =
        LogEvent::from_json(&json!({"attributes": {"timestamp": "2026-11-18T12:01:02.345Z"}}))
            .unwrap();
    assert_eq!(e.timestamp, utc.timestamp);
    let minus =
        LogEvent::from_json(&json!({"attributes": {"timestamp": "2026-11-18T10:01:02.345-02:00"}}))
            .unwrap();
    assert_eq!(minus.timestamp, utc.timestamp);
    for bad in [
        "yesterday",
        "2026-11-18T10:01:02*02:00",
        "2026-11-18T10:01:02+xx:00",
        "2026-11-18T10:01:02+02:yy",
        "Z",
    ] {
        let ev = LogEvent::from_json(&json!({"attributes": {"timestamp": bad}})).unwrap();
        assert!(ev.timestamp.is_none(), "{bad}");
    }
    assert_eq!(e.tags.len(), 3);
    let rec = e.to_log_record();
    assert_eq!(rec.severity, Severity::Error);
    assert_eq!(rec.resource["deployment.environment"], "a");
    assert_eq!(rec.resource["service.version"], "2");
    assert_eq!(
        rec.trace_id.as_deref(),
        Some("4bf92f3577b34da6a3ce929d0e0e4736")
    );
    assert_eq!(rec.span_id.as_deref(), Some("00f067aa0ba902b7"));
    let _: Attributes = rec.attributes;
}
