"""Intake exporter: wire format, gzip, splitting, truncation, error mapping and retries."""

from __future__ import annotations

import asyncio
import gzip
import json
from typing import Any

import pytest

import grow_datadog_logs as sdk
from _support import API_KEY, APP_KEY, MockServer, Reply, dd_config, fast_retry, fixture_reply

INTAKE = "/api/v2/logs"


def logs_of(seen: Any) -> list[dict[str, Any]]:
    body = seen.body
    if seen.headers.get("Content-Encoding") == "gzip":
        body = gzip.decompress(body)
    decoded: list[dict[str, Any]] = json.loads(body)
    return decoded


def test_send_single_record_wire_format(server: MockServer) -> None:
    server.defaults[INTAKE] = fixture_reply("send.datadog_202")
    with sdk.DatadogLogsClient(
        dd_config(server.url, service="svc", tags="team:sdk", env="it")
    ) as c:
        result = c.exporter.export([sdk.LogRecord("hello", attributes={"n": 1})])
    assert result == sdk.ExportResult(accepted=1)
    seen = server.seen[0]
    assert (seen.method, seen.path) == ("POST", INTAKE)
    assert seen.headers["DD-API-KEY"] == API_KEY
    assert "DD-APPLICATION-KEY" not in seen.headers and APP_KEY not in str(seen.headers)
    assert seen.headers["Content-Type"] == "application/json"
    assert seen.headers["Content-Encoding"] == "gzip"
    (log,) = logs_of(seen)
    assert log["message"] == "hello" and log["n"] == 1
    assert (log["service"], log["ddsource"], log["ddtags"]) == (
        "svc",
        "grow-sdk",
        "env:it,team:sdk",
    )
    assert isinstance(log["timestamp"], int)


def test_plain_body_without_gzip(server: MockServer) -> None:
    server.defaults[INTAKE] = Reply(200, {"errors": []})
    with sdk.DatadogLogsClient(dd_config(server.url)) as c:
        assert c.exporter.export([sdk.LogRecord("x")], gzip=False, timeout_s=0).accepted == 1
        assert c.exporter.send_payload("[]", gzip=False) == 0
    assert "Content-Encoding" not in server.seen[0].headers
    assert server.seen[0].body.startswith(b"[{")


def test_encode_splits_on_entries_and_bytes() -> None:
    with sdk.DatadogLogsClient(dd_config("http://unused")) as c:
        exp = c.new_exporter(max_entries_per_request=2)
        batch = exp.encode([sdk.LogRecord(f"m{i}") for i in range(5)])
        assert [p.entries for p in batch.payloads] == [2, 2, 1]
        for p in batch.payloads:
            assert p.size == len(p.body.encode()) and len(json.loads(p.body)) == p.entries
        exp = c.new_exporter(max_payload_bytes=300)
        batch = exp.encode([sdk.LogRecord("x" * 100) for _ in range(4)])
        assert len(batch.payloads) == 4 and all(p.size <= 300 for p in batch.payloads)
        exp = c.new_exporter(max_log_bytes=200)
        batch = exp.encode(
            [sdk.LogRecord("y" * 500), sdk.LogRecord("z", attributes={"big": "a" * 400})]
        )
        assert (batch.truncated, batch.oversized, len(batch.payloads)) == (1, 1, 1)
        # values above the Datadog limits are capped
        capped = c.new_exporter(
            max_entries_per_request=5000, max_payload_bytes=10**9, max_log_bytes=10**9
        )
        assert (capped.max_entries_per_request, capped.max_payload_bytes, capped.max_log_bytes) == (
            sdk.MAX_ENTRIES_PER_REQUEST,
            sdk.MAX_PAYLOAD_BYTES,
            sdk.MAX_LOG_BYTES,
        )
        for bad in (0, -1, True, 1.5):
            with pytest.raises(sdk.ValidationError):
                c.new_exporter(max_entries_per_request=bad)  # type: ignore[arg-type]
        assert c.exporter.encode([]).payloads == []


def test_default_limits_1000_entries(server: MockServer) -> None:
    server.defaults[INTAKE] = Reply(202, {})
    with sdk.DatadogLogsClient(dd_config(server.url)) as c:
        result = c.exporter.export([sdk.LogRecord(f"e{i}") for i in range(2500)])
    assert result.accepted == 2500
    assert [len(logs_of(s)) for s in server.seen] == [1000, 1000, 500]


def test_oversized_metadata_is_rejected(server: MockServer) -> None:
    server.defaults[INTAKE] = Reply(202, {})
    with sdk.DatadogLogsClient(dd_config(server.url)) as c:
        exp = c.new_exporter(max_log_bytes=100)
        result = exp.export([sdk.LogRecord("ok"), sdk.LogRecord("m", attributes={"x": "y" * 200})])
        assert (result.accepted, result.rejected) == (1, 1)
        assert isinstance(result.error, sdk.ValidationError)
        exp.shutdown()


@pytest.mark.parametrize(
    ("name", "error", "attempts"),
    [
        ("send.400_injected", sdk.ValidationError, 1),
        ("send.401_injected", sdk.AuthenticationError, 1),
        ("send.403_invalid_key", sdk.AuthenticationError, 1),
        ("send.403_missing_key", sdk.AuthenticationError, 1),
        ("send.408_injected", sdk.TimeoutError, 2),
        ("send.413_injected", sdk.ValidationError, 1),
        ("send.datadog_413", sdk.ValidationError, 1),
        ("send.429_injected", sdk.RateLimitError, 2),
        ("send.500_injected", sdk.UnavailableError, 2),
        ("send.503_injected", sdk.UnavailableError, 2),
    ],
)
def test_intake_error_mapping_and_retries(
    server: MockServer, name: str, error: type[sdk.DatadogLogsError], attempts: int
) -> None:
    server.defaults[INTAKE] = fixture_reply(name)
    cfg = dd_config(server.url, retry=fast_retry(max_retries=1))
    with sdk.DatadogLogsClient(cfg) as c:
        result = c.exporter.export([sdk.LogRecord("a"), sdk.LogRecord("b")])
    assert (result.accepted, result.rejected) == (0, 2)
    assert isinstance(result.error, error)
    assert result.error.http_status == fixture_reply(name).status
    assert len(server.seen) == attempts


def test_retry_then_success_counts_retries(server: MockServer) -> None:
    server.enqueue(INTAKE, Reply(503, {"errors": ["down"]}), Reply(202, {}))
    with sdk.DatadogLogsClient(dd_config(server.url)) as c:
        assert c.exporter.export([sdk.LogRecord("x")]) == sdk.ExportResult(accepted=1, retries=1)


def test_partial_failure_stops_export(server: MockServer) -> None:
    server.enqueue(INTAKE, Reply(202, {}), Reply(400, {"errors": ["bad"]}))
    with sdk.DatadogLogsClient(dd_config(server.url)) as c:
        result = c.new_exporter(max_entries_per_request=1).export(
            [sdk.LogRecord("1"), sdk.LogRecord("2"), sdk.LogRecord("3")]
        )
    assert (result.accepted, result.rejected) == (1, 2)


def test_unexpected_exception_is_wrapped(
    server: MockServer, monkeypatch: pytest.MonkeyPatch
) -> None:
    with sdk.DatadogLogsClient(dd_config(server.url)) as c:
        exp = c.exporter

        def boom(*a: Any, **k: Any) -> int:
            raise RuntimeError("boom")

        monkeypatch.setattr(exp, "send_payload", boom)
        result = exp.export([sdk.LogRecord("x")])
    assert isinstance(result.error, sdk.UnavailableError)
    assert isinstance(result.error.cause, RuntimeError)


def test_log_client_end_to_end(server: MockServer) -> None:
    server.defaults[INTAKE] = Reply(202, {})
    with sdk.DatadogLogsClient(dd_config(server.url, hostname="h")) as c:
        lc = c.log_client(sdk.BatchOptions(flush_interval_s=3600))
        lc.logger("app", {"component": "x"}).warn("careful", {"password": "hunter2"})
        lc.send(sdk.LogInput("traced", trace_id="4bf92f3577b34da6a3ce929d0e0e4736"))
        lc.flush(5)
        assert lc.stats().sent == 2
    logs = [log for s in server.seen for log in logs_of(s)]
    warn = next(log for log in logs if log["message"] == "careful")
    assert warn["status"] == "warn" and warn["password"] == "[REDACTED]"
    assert warn["logger.name"] == "app" and warn["hostname"] == "h"
    traced = next(log for log in logs if log["message"] == "traced")
    assert traced["dd.trace_id"] == "11803532876627986230"


async def test_async_exporter_and_log_client(server: MockServer) -> None:
    server.enqueue(INTAKE, Reply(503, {}), Reply(202, {}))
    server.defaults[INTAKE] = Reply(202, {})
    async with sdk.AsyncDatadogLogsClient(dd_config(server.url, service="svc")) as c:
        result = await c.exporter.export([sdk.LogRecord("a")])
        assert result == sdk.ExportResult(accepted=1, retries=1)
        assert await c.exporter.send_payload("[]", gzip=False, timeout_s=1) == 0
        small = c.new_exporter(max_entries_per_request=1, max_log_bytes=250)
        res = await small.export(
            [sdk.LogRecord("1"), sdk.LogRecord("x", attributes={"y": "z" * 400})]
        )
        assert (res.accepted, res.rejected) == (1, 1)
        lc = c.log_client(sdk.BatchOptions(flush_interval_s=3600), exporter=small)
        lc.send(sdk.LogInput("from async"))
        await lc.flush(5)
        assert lc.stats().sent == 1
        await small.shutdown()
    server.defaults[INTAKE] = Reply(400, {"errors": ["bad"]})
    async with sdk.AsyncDatadogLogsClient(dd_config(server.url)) as c:
        res = await c.exporter.export([sdk.LogRecord("a")])
        assert isinstance(res.error, sdk.ValidationError)


async def test_async_export_cancellation_propagates(server: MockServer) -> None:
    server.defaults[INTAKE] = Reply(202, {}, delay=2.0)
    c = sdk.AsyncDatadogLogsClient(dd_config(server.url))
    task = asyncio.ensure_future(c.exporter.export([sdk.LogRecord("slow")]))
    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    await c.aclose()
