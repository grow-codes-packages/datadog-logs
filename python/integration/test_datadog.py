"""Datadog functions against datadog/fakeintake: intake round trips, limits, faults, query stubs.

Intake calls are real round trips: the SDK posts, the test reads the captured payload back from
the container and asserts what arrived. fakeintake has no search engine, so the query calls run
against "wire stubs" (fixed responses installed with an override); the request the SDK sent is
still captured and asserted.
"""

from __future__ import annotations

import asyncio
import json
import logging
import secrets
import time
from datetime import datetime, timedelta, timezone
from typing import Any

import pytest
from factories import async_core_client, core_client
from fakeintake import FakeIntake

import grow_datadog_logs as sdk

TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
SPAN = "00f067aa0ba902b7"


def run_id() -> str:
    return "py-" + secrets.token_hex(5)


def by_prefix(logs: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {str(log["message"]).split()[0]: log for log in logs}


def test_config_and_sites(env: dict[str, str]) -> None:
    cfg = sdk.DatadogLogsConfig.from_env(env)
    assert cfg.intake_url == cfg.api_url == env["GROW_DATADOG_LOGS_URL"]
    assert cfg.api_key is not None and cfg.app_key is not None
    assert cfg.api_key.reveal() not in repr(cfg)
    assert sdk.resolve_site(cfg.site).api_url == "https://api.datadoghq.com"
    assert sdk.parse_tags("a:1, b:2") == ("a:1", "b:2")


def test_validate_api_key(client: sdk.DatadogLogsClient) -> None:
    assert client.validate_api_key() is True


def test_ingest_round_trip(env: dict[str, str], intake: FakeIntake) -> None:
    run = run_id()
    c = core_client(
        env,
        service="cfg-svc",
        env="it",
        version="1.2.3",
        hostname="py-host",
        tags=["team:sdk", "lang:py"],
    )
    lc = c.log_client(sdk.BatchOptions(flush_interval_s=3600))
    lc.send(
        sdk.LogInput(
            f"input {run}",
            severity=sdk.Severity.WARN,
            attributes={
                "run": run,
                "service": "user-value",
                "password": "hunter2",
                "user": {"id": 42},
            },
        )
    )
    lc.send_batch(
        [
            sdk.LogRecord(
                body=f'record {run} Grüße – 日本語   "quoted"',
                severity=sdk.Severity.ERROR,
                timestamp=datetime.now(timezone.utc) - timedelta(minutes=1),
                resource={"service.name": "checkout", "deployment.environment": "test"},
                trace_id=TRACE,
                span_id=SPAN,
                trace_flags=1,
            )
        ]
    )
    lc.logger("it-logger", {"component": "facade"}).info(f"logger {run}", {"k": "v"})
    pylog = logging.getLogger(f"grow.it.{run}")
    pylog.propagate = False
    pylog.addHandler(sdk.LogHandler(lc))
    pylog.error("stdlib %s", run, extra={"attempt": 3})
    sdk.structlog_processor(lc)(None, "info", {"event": f"structlog {run}", "level": "debug"})
    lc.flush(30)
    assert lc.stats() == sdk.BatchStats(queued=0, sent=5, dropped=0, failed=0, retries=0)

    logs, _ = intake.logs_with(run)
    assert len(logs) == 5, logs
    got = by_prefix(logs)
    entry = got["input"]
    assert {k: entry[k] for k in ("status", "service", "hostname", "ddsource", "ddtags")} == {
        "status": "warn",
        "service": "cfg-svc",
        "hostname": "py-host",
        "ddsource": "grow-sdk",
        "ddtags": "env:it,version:1.2.3,team:sdk,lang:py",
    }
    assert entry["attr.service"] == "user-value" and entry["password"] == "[REDACTED]"
    assert entry["run"] == run and entry["user"] == {"id": 42}
    assert isinstance(entry["timestamp"], int)
    rec = got["record"]
    assert rec["status"] == "error" and rec["service"] == "checkout"
    assert rec["ddtags"] == "env:test,version:1.2.3,team:sdk,lang:py"
    assert (rec["dd.trace_id"], rec["dd.span_id"]) == ("11803532876627986230", "67667974448284343")
    assert (rec["otel.trace_id"], rec["otel.span_id"]) == (TRACE, SPAN)
    assert 'Grüße – 日本語   "quoted"' in rec["message"]
    logger_log = got["logger"]
    assert (logger_log["logger.name"], logger_log["component"], logger_log["k"]) == (
        "it-logger",
        "facade",
        "v",
    )
    assert got["stdlib"]["status"] == "error" and got["stdlib"]["attempt"] == 3
    assert got["structlog"]["status"] == "debug"

    # close() flushes every log client it created
    marker = run_id()
    lc2 = c.log_client(sdk.BatchOptions(flush_interval_s=3600))
    lc2.send(sdk.LogInput(f"closing {marker}"))
    c.close()
    c.close()
    assert len(intake.logs_with(marker)[0]) == 1


def test_exporter_plain_and_send_payload(client: sdk.DatadogLogsClient, intake: FakeIntake) -> None:
    marker = run_id()
    exp = client.exporter
    result = exp.export([sdk.LogRecord(f"plain {marker}")], gzip=False, timeout_s=10)
    assert result == sdk.ExportResult(accepted=1)
    batch = exp.encode([sdk.LogRecord(f"payload {marker}")])
    assert len(batch.payloads) == 1 and batch.payloads[0].entries == 1
    assert exp.send_payload(batch.payloads[0].body, gzip=True) == 0
    logs, _ = intake.logs_with(marker)
    assert sorted(str(log["message"]).split()[0] for log in logs) == ["payload", "plain"]
    assert all(log["status"] == "info" for log in logs)
    exp.shutdown()


def test_intake_limits(client: sdk.DatadogLogsClient, intake: FakeIntake) -> None:
    marker = run_id()
    result = client.exporter.export([sdk.LogRecord(f"entry {marker}") for _ in range(2500)])
    assert result.accepted == 2500
    logs, payloads = intake.logs_with(marker)
    assert len(logs) == 2500 and len(payloads) == 3
    assert all(len(p.logs) <= sdk.MAX_ENTRIES_PER_REQUEST for p in payloads)

    marker = run_id()
    big = "x" * 900_000
    result = client.exporter.export([sdk.LogRecord(f"big {marker} {big}") for _ in range(12)])
    assert result.accepted == 12
    logs, payloads = intake.logs_with(marker)
    assert len(logs) == 12 and len(payloads) >= 3
    assert all(len(p.raw) <= sdk.MAX_PAYLOAD_BYTES for p in payloads)

    marker = run_id()
    result = client.exporter.export([sdk.LogRecord(f"huge {marker} " + "y" * 1_100_000)])
    assert result.accepted == 1
    logs, payloads = intake.logs_with(marker)
    assert len(logs) == 1 and logs[0][sdk.TRUNCATED_ATTRIBUTE] is True
    assert len(payloads[0].raw) - 2 <= sdk.MAX_LOG_BYTES

    marker = run_id()
    small = client.new_exporter(max_entries_per_request=10)
    assert small.export([sdk.LogRecord(f"small {marker}") for _ in range(25)]).accepted == 25
    assert [len(p.logs) for p in intake.logs_with(marker)[1]] == [10, 10, 5]


@pytest.mark.parametrize(
    ("status", "body", "error", "attempts"),
    [
        (400, '{"errors":["Bad Request"]}', sdk.ValidationError, 1),
        (
            403,
            '{"errors":["Forbidden","API key is missing or invalid"]}',
            sdk.AuthenticationError,
            1,
        ),
        (413, '{"errors":["Payload too large"]}', sdk.ValidationError, 1),
        (429, '{"errors":["Too many requests"]}', sdk.RateLimitError, 2),
        (500, '{"errors":["Internal Server Error"]}', sdk.UnavailableError, 2),
        (503, '{"errors":["Service Unavailable"]}', sdk.UnavailableError, 2),
    ],
)
def test_intake_errors(
    env: dict[str, str],
    intake: FakeIntake,
    status: int,
    body: str,
    error: type[sdk.DatadogLogsError],
    attempts: int,
) -> None:
    intake.override("POST", "/api/v2/logs", status, body)
    marker = run_id()
    with core_client(env, retry=sdk.RetryPolicy(max_retries=1, initial_backoff=0.001)) as c:
        result = c.exporter.export([sdk.LogRecord(f"fault {marker}")])
    assert isinstance(result.error, error) and result.error.http_status == status
    assert (result.accepted, result.rejected) == (0, 1)
    assert len(intake.logs_with(marker)[0]) == attempts


def search_stub(run: str) -> str:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    return json.dumps(
        {
            "data": [
                {
                    "id": "stub-1",
                    "type": "log",
                    "attributes": {
                        "timestamp": ts,
                        "host": "py-host",
                        "service": "checkout",
                        "status": "error",
                        "message": f"stub {run}",
                        "tags": ["env:it", "version:1.2.3"],
                        "attributes": {
                            "dd": {
                                "trace_id": "11803532876627986230",
                                "span_id": "67667974448284343",
                            },
                            "run": run,
                        },
                    },
                }
            ],
            "meta": {"elapsed": 5, "request_id": f"req-{run}", "status": "done"},
        }
    )


AGGREGATE_STUB = (
    '{"data":{"buckets":[{"by":{"service":"checkout"},"computes":{"c0":3}}]},'
    '"meta":{"elapsed":7,"request_id":"agg","status":"done"}}'
)
INDEXES_STUB = (
    '{"indexes":[{"name":"main","filter":{"query":"*"},"num_retention_days":15,'
    '"daily_limit":300000000,"is_rate_limited":false,"exclusion_filters":[]}]}'
)


def install_query_stubs(intake: FakeIntake, run: str) -> None:
    intake.override("POST", "/api/v2/logs/events/search", 200, search_stub(run))
    intake.override("POST", "/api/v2/logs/analytics/aggregate", 200, AGGREGATE_STUB)
    intake.override("GET", "/api/v1/logs/config/indexes", 200, INDEXES_STUB)


def test_query_wire_stubs(client: sdk.DatadogLogsClient, intake: FakeIntake) -> None:
    run = run_id()
    install_query_stubs(intake, run)
    q = client.query()

    page = q.search_logs(f'service:checkout "{run}"', from_="now-1h", limit=50, indexes=["main"])
    assert len(page.events) == 1 and page.request_id == f"req-{run}" and page.next_cursor is None
    rec = page.records[0]
    assert rec.severity == sdk.Severity.ERROR
    assert (rec.trace_id, rec.span_id) == ("0000000000000000a3ce929d0e0e4736", SPAN)
    assert rec.resource["service.version"] == "1.2.3"
    sent = json.loads(intake.payloads("/api/v2/logs/events/search")[-1].raw)
    assert sent["filter"] == {
        "query": f'service:checkout "{run}"',
        "from": "now-1h",
        "to": "now",
        "indexes": ["main"],
        "storage_tier": "indexes",
    }
    assert sent["sort"] == "-timestamp" and sent["page"] == {"limit": 50}

    assert [e.id for e in q.iterate_logs(run, limit=10)] == ["stub-1"]
    result = q.search(run, start=datetime.now(timezone.utc) - timedelta(hours=1))
    assert [r.body for r in result.records] == [f"stub {run}"]
    assert q.wait_for_log(run, timeout_s=10, interval_s=0.2).body == f"stub {run}"

    agg = q.aggregate_logs(sdk.DatadogCompute("count"), [sdk.DatadogGroupBy("service")], query=run)
    assert agg.buckets[0].number("c0") == 3.0 and agg.buckets[0].by == {"service": "checkout"}
    sent = json.loads(intake.payloads("/api/v2/logs/analytics/aggregate")[-1].raw)
    assert sent["compute"] == [{"aggregation": "count", "type": "total"}]
    assert sent["group_by"] == [{"facet": "service"}] and sent["filter"]["query"] == run

    indexes = q.list_indexes()
    assert indexes[0].name == "main" and indexes[0].retention_days == 15
    assert q.list_streams() == ["main"]
    with pytest.raises(sdk.UnsupportedError):
        q.list_labels()

    got: list[sdk.LogRecord] = []
    handle = q.tail(
        run, got.append, interval_s=0.1, start=datetime.now(timezone.utc) - timedelta(hours=1)
    )
    deadline = time.monotonic() + 10
    while not got and time.monotonic() < deadline:
        time.sleep(0.1)
    handle.stop()
    assert [r.body for r in got] == [f"stub {run}"]


def test_query_against_fakeintake_default_is_a_protocol_error(
    client: sdk.DatadogLogsClient, intake: FakeIntake
) -> None:
    """Without a stub fakeintake answers 200 {"errors":[]}: never an empty result."""
    intake.override("POST", "/api/v2/logs/events/search", 200, '{"errors":[]}')
    with pytest.raises(sdk.UnavailableError, match="no data array"):
        client.query().search_logs("x")


def test_async_round_trip_and_query(env: dict[str, str], intake: FakeIntake) -> None:
    run = run_id()
    install_query_stubs(intake, run)

    async def scenario() -> None:
        async with async_core_client(env, service="async-svc") as c:
            lc = c.log_client(sdk.BatchOptions(flush_interval_s=3600))
            lc.send(sdk.LogInput(f"async {run}", attributes={"n": 1}))
            assert await lc.send_wait(sdk.LogInput(f"waited {run}"))
            await lc.flush(30)
            assert lc.stats().sent == 2
            res = await c.exporter.export([sdk.LogRecord(f"direct {run}")])
            assert res.accepted == 1
            assert (
                await c.new_exporter(max_entries_per_request=1).send_payload("[]", gzip=False) == 0
            )

            q = c.query()
            page = await q.search_logs(run)
            assert page.events[0].message == f"stub {run}"
            assert [e.id async for e in q.iterate_logs(run, limit=5)] == ["stub-1"]
            assert len((await q.search(run)).records) == 1
            assert (await q.wait_for_log(run, interval_s=0.2)).body == f"stub {run}"
            agg = await q.aggregate_logs(sdk.DatadogCompute("count"))
            assert agg.buckets[0].number("c0") == 3.0
            assert [i.name for i in await q.list_indexes()] == ["main"]
            assert await q.list_streams() == ["main"]
            with pytest.raises(sdk.UnsupportedError):
                await q.list_labels()
            got: list[sdk.LogRecord] = []
            await q.tail(
                run,
                got.append,
                interval_s=0.1,
                start=datetime.now(timezone.utc) - timedelta(hours=1),
            )
            for _ in range(100):
                if got:
                    break
                await asyncio.sleep(0.1)
            assert got and got[0].body == f"stub {run}"
            closing = c.log_client(sdk.BatchOptions(flush_interval_s=3600))
            closing.send(sdk.LogInput(f"closing {run}"))

    asyncio.run(scenario())
    got = by_prefix(intake.logs_with(run)[0])
    assert set(got) == {"async", "waited", "direct", "closing"}
    assert got["async"]["service"] == "async-svc" and got["async"]["n"] == 1
