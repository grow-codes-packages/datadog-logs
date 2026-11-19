"""LogRecord <-> Datadog JSON mapping, truncation and search-event normalization."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

import grow_datadog_logs as sdk
from _support import fixture

TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
SPAN = "00f067aa0ba902b7"


def test_trace_and_span_ids() -> None:
    assert sdk.trace_id_to_datadog(TRACE) == "11803532876627986230"
    assert sdk.trace_id_to_datadog(" A3CE929D0E0E4736 ") == "11803532876627986230"
    for bad in (None, "", "xyz", "1" * 33):
        assert sdk.trace_id_to_datadog(bad) is None
    assert sdk.span_id_to_datadog(SPAN) == "67667974448284343"
    for bad in (None, "", "zz", "1" * 17):
        assert sdk.span_id_to_datadog(bad) is None
    assert sdk.datadog_id_to_hex("11803532876627986230", 32) == "0" * 16 + "a3ce929d0e0e4736"
    assert sdk.datadog_id_to_hex(67667974448284343, 16) == SPAN
    assert sdk.datadog_id_to_hex(TRACE.upper(), 32) == TRACE
    for bad in (True, 0, -1, None, 1.5, "0", "g1", "1" * 21, "f" * 17, str(1 << 64)):
        assert sdk.datadog_id_to_hex(bad, 16) is None


def test_status_and_severity() -> None:
    assert sdk.datadog_status(sdk.LogRecord("x", severity=sdk.Severity.WARN)) == "warn"
    assert sdk.datadog_status(sdk.LogRecord("x", severity_text=" Notice ")) == "notice"
    rec = sdk.LogRecord("x", severity=sdk.Severity.ERROR)
    object.__setattr__(rec, "severity_text", "  ")
    assert sdk.datadog_status(rec) == "error"
    expected = {
        "emerg": sdk.Severity.FATAL,
        "critical": sdk.Severity.FATAL,
        "err": sdk.Severity.ERROR,
        "warning": sdk.Severity.WARN,
        "ok": sdk.Severity.INFO,
        "debug": sdk.Severity.DEBUG,
        "TRACE": sdk.Severity.TRACE,
        "weird": sdk.Severity.INFO,
        None: sdk.Severity.INFO,
    }
    for status, severity in expected.items():
        assert sdk.severity_from_status(status) == severity


def test_build_ddtags() -> None:
    assert sdk.build_ddtags(None, None) is None
    assert (
        sdk.build_ddtags("prod", "1.0", ["team:sdk", "env:prod", "a b,c", ""])
        == "env:prod,version:1.0,team:sdk,a_b_c"
    )


def test_to_datadog_entry_full() -> None:
    ts = datetime(2026, 11, 18, 12, 0, 0, 123456, tzinfo=timezone.utc)
    record = sdk.LogRecord(
        body="user signed in",
        severity=sdk.Severity.ERROR,
        timestamp=ts,
        attributes={"user": {"id": 42}, "service": "user-value", "message": "m", "k": "v"},
        resource={
            "service.name": "checkout",
            "host.name": "host-a",
            "deployment.environment": "test",
            "service.version": "1.2.3",
            "ignored": "x",
        },
        trace_id=TRACE,
        span_id=SPAN,
    )
    entry = sdk.to_datadog_entry(
        record, sdk.EntryDefaults(service="d", tags=("team:sdk",), source="grow-sdk")
    )
    assert entry == {
        "message": "user signed in",
        "timestamp": 1795003200123,
        "status": "error",
        "service": "checkout",
        "hostname": "host-a",
        "ddsource": "grow-sdk",
        "ddtags": "env:test,version:1.2.3,team:sdk",
        "dd.trace_id": "11803532876627986230",
        "dd.span_id": "67667974448284343",
        "otel.trace_id": TRACE,
        "otel.span_id": SPAN,
        "user": {"id": 42},
        "attr.service": "user-value",
        "attr.message": "m",
        "k": "v",
    }


def test_to_datadog_entry_defaults_and_partial_ids() -> None:
    naive = datetime(1970, 1, 1, 0, 0, 1)
    record = sdk.LogRecord(
        "x",
        timestamp=naive,
        resource={"service.name": {"nested": 1}, "host.name": " "},
        trace_id="A3CE929D0E0E4736",
        span_id="nothex",
    )
    entry = sdk.to_datadog_entry(
        record, sdk.EntryDefaults(hostname="h", env="dev", version="2", source=None)
    )
    assert entry["timestamp"] == 1000
    assert entry["service"] == '{"nested":1}'
    assert entry["hostname"] == "h"
    assert entry["ddtags"] == "env:dev,version:2"
    assert "ddsource" not in entry and "dd.span_id" not in entry and "otel.span_id" not in entry
    assert entry["otel.trace_id"] == "0" * 16 + "a3ce929d0e0e4736"
    bare = sdk.to_datadog_entry(sdk.LogRecord("y", trace_id="zz"))
    assert set(bare) == {"message", "timestamp", "status"}


def test_serialize_entry_truncates_at_limit() -> None:
    small = sdk.serialize_entry({"message": "hi", "timestamp": 1, "status": "info"})
    assert small is not None and not small.truncated
    assert small.size == len(small.json) and json.loads(small.json)["message"] == "hi"
    big = {"message": "é" * 600_000 + "tail", "timestamp": 1, "status": "info"}
    out = sdk.serialize_entry(big)
    assert out is not None and out.truncated
    assert out.size <= sdk.MAX_LOG_BYTES and out.size == len(out.json.encode())
    decoded = json.loads(out.json)
    assert decoded[sdk.TRUNCATED_ATTRIBUTE] is True
    assert decoded["message"].startswith("éé") and not decoded["message"].endswith("tail")
    assert out.size > sdk.MAX_LOG_BYTES - 4
    escaped = sdk.serialize_entry({"message": '"\n' * 100, "x": 1}, max_bytes=120)
    assert escaped is not None and escaped.size <= 120
    assert sdk.serialize_entry({"message": "m", "meta": "x" * 200}, max_bytes=100) is None


def test_parse_log_event_and_record_from_fixture() -> None:
    data = fixture("searchLogs.page1")["response"]["body"]["data"]
    event = sdk.parse_log_event(data[0])
    assert event is not None
    assert event.id and event.message and event.service == "checkout"
    assert event.timestamp is not None and event.timestamp.tzinfo is not None
    record = event.record()
    assert record.body == event.message
    assert record.resource["service.name"] == "checkout"
    assert record.attributes["datadog.log_id"] == event.id
    assert "env:test" in record.attributes["datadog.tags"]  # type: ignore[operator]


def test_parse_log_event_edge_cases() -> None:
    assert sdk.parse_log_event("nope") is None
    event = sdk.parse_log_event(
        {"attributes": {"timestamp": "not a time", "tags": "x", "attributes": []}}
    )
    assert event == sdk.DatadogLogEvent(id="", message="")
    assert event.record().timestamp == datetime(1970, 1, 1, tzinfo=timezone.utc)
    assert sdk.parse_log_event({"id": 5, "attributes": "x"}) == sdk.DatadogLogEvent("", "")


def test_event_to_log_record_trace_ids() -> None:
    otel = sdk.DatadogLogEvent(
        id="1",
        message="m",
        status="warning",
        host="h",
        tags=("env:prod", "version:3", "team:x"),
        attributes={"otel.trace_id": TRACE, "otel": {"span_id": SPAN}, "n": None, "l": [1, None]},
        timestamp=datetime.now(timezone.utc) - timedelta(seconds=1),
    )
    rec = sdk.event_to_log_record(otel)
    assert (rec.trace_id, rec.span_id, rec.severity, rec.severity_text) == (
        TRACE,
        SPAN,
        sdk.Severity.WARN,
        "warning",
    )
    assert rec.resource == {
        "host.name": "h",
        "deployment.environment": "prod",
        "service.version": "3",
    }
    assert rec.attributes["l"] == [1] and "n" not in rec.attributes
    dd = sdk.DatadogLogEvent(
        id="",
        message="m",
        attributes={"dd": {"trace_id": "11803532876627986230", "span_id": "67667974448284343"}},
    )
    rec = sdk.event_to_log_record(dd)
    assert rec.trace_id == "0" * 16 + "a3ce929d0e0e4736" and rec.span_id == SPAN
    assert "datadog.log_id" not in rec.attributes and "datadog.tags" not in rec.attributes
    assert rec.severity_text == "info"
    no_trace = sdk.event_to_log_record(sdk.DatadogLogEvent("", "m", attributes={"dd": "x"}))
    assert no_trace.trace_id is None and no_trace.span_id is None


@pytest.mark.parametrize(
    "value", [datetime(2026, 1, 2, 3, 4, 5, 678000), 1767323045678, 1767323045678.0]
)
def test_format_datadog_time(value: object) -> None:
    assert sdk.format_datadog_time(value) == "2026-01-02T03:04:05.678Z"  # type: ignore[arg-type]


def test_format_datadog_time_strings_and_errors() -> None:
    aware = datetime(2026, 1, 2, 4, 4, 5, tzinfo=timezone(timedelta(hours=1)))
    assert sdk.format_datadog_time(aware) == "2026-01-02T03:04:05.000Z"
    assert sdk.format_datadog_time(" now-15m ") == "now-15m"
    for bad in ("  ", True, 1e30):
        with pytest.raises(sdk.ValidationError):
            sdk.format_datadog_time(bad)  # type: ignore[arg-type]
