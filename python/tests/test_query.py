"""Query client (sync + async): search, pagination, aggregate, indexes, tail, wait_for_log."""

from __future__ import annotations

import asyncio
import json
import threading
import time
from datetime import datetime, timezone
from typing import Any

import pytest

import grow_datadog_logs as sdk
from _support import API_KEY, APP_KEY, MockServer, Reply, dd_config, fixture, fixture_reply

SEARCH = "/api/v2/logs/events/search"
AGGREGATE = "/api/v2/logs/analytics/aggregate"
INDEXES = "/api/v1/logs/config/indexes"


def query(server: MockServer, **kw: Any) -> tuple[sdk.DatadogLogsClient, sdk.DatadogLogsQuery]:
    c = sdk.DatadogLogsClient(dd_config(server.url, **kw))
    return c, c.query()


def body_of(seen: Any) -> dict[str, Any]:
    decoded: dict[str, Any] = json.loads(seen.body)
    return decoded


def event_reply(*messages: str, after: str | None = None, ts: str | None = None) -> Reply:
    stamp = ts or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    data = [
        {
            "id": f"id-{m}",
            "type": "log",
            "attributes": {"timestamp": stamp, "message": m, "status": "info"},
        }
        for m in messages
    ]
    meta: dict[str, Any] = {"status": "done"}
    if after:
        meta["page"] = {"after": after}
    return Reply(200, {"data": data, "meta": meta})


def test_search_logs_request_and_page(server: MockServer) -> None:
    server.enqueue(SEARCH, fixture_reply("searchLogs.page1"))
    c, q = query(server)
    with c:
        page = q.search_logs(
            'service:checkout "run-7f3a"',
            from_=datetime(2026, 11, 18, 12, tzinfo=timezone.utc),
            to=1795003500000,
            limit=2,
            indexes=["main"],
            storage_tier="flex",
            sort="timestamp",
        )
    seen = server.seen[0]
    assert seen.headers["DD-API-KEY"] == API_KEY and seen.headers["DD-APPLICATION-KEY"] == APP_KEY
    assert body_of(seen) == {
        "filter": {
            "query": 'service:checkout "run-7f3a"',
            "from": "2026-11-18T12:00:00.000Z",
            "to": "2026-11-18T12:05:00.000Z",
            "indexes": ["main"],
            "storage_tier": "flex",
        },
        "sort": "timestamp",
        "page": {"limit": 2},
    }
    assert len(page.events) == 2 and len(page.records) == 2
    assert page.next_cursor and page.status == "done" and page.request_id
    assert page.elapsed_ms == 38.0 and page.warnings == []


def test_search_logs_defaults(server: MockServer) -> None:
    server.enqueue(SEARCH, fixture_reply("searchLogs.empty"))
    c, q = query(server)
    with c:
        page = q.search_logs(indexes="main", cursor="abc")  # type: ignore[arg-type]
    assert page.events == [] and page.next_cursor is None
    assert body_of(server.seen[0]) == {
        "filter": {
            "query": "*",
            "from": "now-15m",
            "to": "now",
            "indexes": ["main"],
            "storage_tier": "indexes",
        },
        "sort": "-timestamp",
        "page": {"limit": 100, "cursor": "abc"},
    }


def test_search_logs_partial_results(server: MockServer) -> None:
    server.enqueue(SEARCH, fixture_reply("searchLogs.warnings_partial"))
    server.enqueue(SEARCH, fixture_reply("searchLogs.timeout_status"))
    c, q = query(server)
    with c:
        page = q.search_logs("x")
        assert page.warnings[0].code == "unknown_index" and page.warnings[0].detail
        rec = page.records[0]
        assert rec.trace_id == "4bf92f3577b34da6a3ce929d0e0e4736"
        assert rec.span_id == "00f067aa0ba902b7"
        result = q.search("x", order="asc")
        assert isinstance(result, sdk.LogSearchResult) and result.status == "timeout"
        assert result.total is None


@pytest.mark.parametrize(
    ("name", "error"),
    [
        ("searchLogs.400_invalid_query", sdk.ValidationError),
        ("searchLogs.401_missing_keys", sdk.AuthenticationError),
        ("searchLogs.403_invalid_keys", sdk.AuthenticationError),
    ],
)
def test_search_errors(server: MockServer, name: str, error: type[sdk.DatadogLogsError]) -> None:
    server.defaults[SEARCH] = fixture_reply(name)
    c, q = query(server)
    with c, pytest.raises(error) as info:
        q.search_logs("x")
    assert info.value.http_status == fixture(name)["response"]["status"]
    assert len(server.seen) == 1


def test_search_rate_limited_uses_reset_header(server: MockServer) -> None:
    server.defaults[SEARCH] = fixture_reply("searchLogs.429_rate_limited")
    c, q = query(server)
    with c, pytest.raises(sdk.RateLimitError) as info:
        q.search_logs("x")
    assert info.value.retry_after == 1820.0


def test_search_retries_5xx(server: MockServer) -> None:
    server.enqueue(SEARCH, Reply(500, {"errors": ["oops"]}), fixture_reply("searchLogs.empty"))
    c, q = query(server)
    with c:
        assert q.search_logs("x").events == []
    assert len(server.seen) == 2


def test_protocol_errors(server: MockServer) -> None:
    c, q = query(server)
    with c:
        server.enqueue(SEARCH, fixture_reply("searchLogs.fakeintake_default"))
        with pytest.raises(sdk.UnavailableError, match="no data array") as info:
            q.search_logs("x")
        assert not info.value.retryable
        server.enqueue(SEARCH, Reply(200, "not json"))
        with pytest.raises(sdk.UnavailableError, match="no JSON object"):
            q.search_logs("x")
        server.enqueue(AGGREGATE, Reply(200, {"data": []}))
        with pytest.raises(sdk.UnavailableError, match=r"data\.buckets"):
            q.aggregate_logs(sdk.DatadogCompute("count"))
        server.enqueue(INDEXES, fixture_reply("fakeintake.unknownGet.default"))
        with pytest.raises(sdk.UnavailableError):
            q.list_indexes()
        server.enqueue(INDEXES, Reply(200, {"nope": 1}))
        with pytest.raises(sdk.UnavailableError, match="indexes array"):
            q.list_streams()


def test_input_validation(server: MockServer) -> None:
    c, q = query(server)
    with c:
        for kwargs in ({"limit": 0}, {"limit": 5001}, {"limit": True}, {"sort": "up"}):
            with pytest.raises(sdk.ValidationError):
                q.search_logs("x", **kwargs)  # type: ignore[arg-type]
        with pytest.raises(sdk.ValidationError):
            list(q.iterate_logs("x", limit=0))
        with pytest.raises(sdk.ValidationError):
            list(q.iterate_logs("x", page_size=6000))
        with pytest.raises(sdk.ValidationError, match="must not be empty"):
            q.aggregate_logs([])
        with pytest.raises(sdk.ValidationError, match="aggregation"):
            q.aggregate_logs({"aggregation": " "})
        with pytest.raises(sdk.ValidationError, match="facet"):
            q.aggregate_logs(sdk.DatadogCompute("count"), [{"facet": ""}])
    assert server.seen == []


def test_iterate_logs_pages(server: MockServer) -> None:
    server.enqueue(
        SEARCH, fixture_reply("searchLogs.page1"), fixture_reply("searchLogs.page2_last")
    )
    c, q = query(server)
    with c:
        events = list(q.iterate_logs("run-7f3a", page_size=2))
    assert len(events) >= 3
    first, second = body_of(server.seen[0]), body_of(server.seen[1])
    assert "cursor" not in first["page"] and first["page"]["limit"] == 2
    assert (
        second["page"]["cursor"]
        == fixture("searchLogs.page1")["response"]["body"]["meta"]["page"]["after"]
    )


def test_iterate_logs_limit_and_loop_guard(server: MockServer) -> None:
    c, q = query(server)
    with c:
        server.enqueue(SEARCH, event_reply("a", "b", after="c1"), event_reply("c", after="c2"))
        assert [e.message for e in q.iterate_logs("x", limit=3, page_size=2)] == ["a", "b", "c"]
        assert [body_of(s)["page"]["limit"] for s in server.seen] == [2, 1]
        server.enqueue(SEARCH, event_reply("a", "b", "c", after="c1"))
        assert len(list(q.iterate_logs("x", limit=2))) == 2  # server sent more than asked
        server.enqueue(SEARCH, event_reply(after="c9"))
        assert list(q.iterate_logs("x")) == []
        server.enqueue(SEARCH, event_reply("a", after="same"), event_reply("b", after="same"))
        with pytest.raises(sdk.UnavailableError, match="repeated a page cursor"):
            list(q.iterate_logs("x"))


def test_aggregate_logs(server: MockServer) -> None:
    server.enqueue(AGGREGATE, fixture_reply("aggregateLogs.count_by_service"))
    server.enqueue(AGGREGATE, fixture_reply("aggregateLogs.timeseries"))
    server.enqueue(AGGREGATE, fixture_reply("aggregateLogs.paged"))
    c, q = query(server)
    with c:
        res = q.aggregate_logs(
            sdk.DatadogCompute("count"),
            [
                sdk.DatadogGroupBy(
                    "service", limit=10, sort=sdk.DatadogGroupBySort("count", order="desc")
                )
            ],
            query='service:checkout "run-7f3a"',
            from_="now-1h",
            indexes=["main"],
        )
        assert res.buckets[0].by == {"service": "checkout"}
        assert res.buckets[0].number("c0") == 3.0 and res.buckets[0].number("c9") is None
        assert res.buckets[0].timeseries("c0") == [] and res.next_cursor is None
        ts = q.aggregate_logs(
            [{"aggregation": "count", "type": "timeseries", "interval": "5m"}],
            [
                {
                    "facet": "@duration",
                    "missing": 0,
                    "total": True,
                    "histogram": {"interval": 10, "min": 0, "max": 100},
                    "sort": {"order": "asc"},
                }
            ],
            cursor="cur",
        )
        points = ts.buckets[0].timeseries("c0")
        assert [p.value for p in points] == [3.0, 0.0, 0.0]
        assert points[0].time == datetime(2026, 11, 18, 12, tzinfo=timezone.utc)
        assert ts.buckets[0].number("c0") is None
        paged = q.aggregate_logs(sdk.DatadogCompute("avg", metric="@duration"))
        assert paged.next_cursor and paged.buckets[0].by == {"@user.id": 42}
    first, second, third = (body_of(s) for s in server.seen)
    assert first["compute"] == [{"aggregation": "count", "type": "total"}]
    assert first["group_by"] == [
        {"facet": "service", "limit": 10, "sort": {"aggregation": "count", "order": "desc"}}
    ]
    assert first["filter"]["from"] == "now-1h" and "page" not in first
    assert second["compute"][0]["interval"] == "5m" and second["page"] == {"cursor": "cur"}
    assert second["group_by"][0]["histogram"] == {"interval": 10, "min": 0, "max": 100}
    assert second["group_by"][0]["missing"] == 0 and second["group_by"][0]["total"] is True
    assert third["compute"] == [{"aggregation": "avg", "type": "total", "metric": "@duration"}]
    assert "group_by" not in third


def test_aggregate_parsing_skips_bad_values(server: MockServer) -> None:
    server.enqueue(
        AGGREGATE,
        Reply(
            200,
            {
                "data": {
                    "buckets": [
                        "junk",
                        {"by": {"a": "x", "b": True, "c": None}, "computes": {"c0": "str"}},
                        {
                            "computes": {
                                "c0": [
                                    {"time": "bad", "value": 1},
                                    "junk",
                                    {"time": "2026-11-18T12:00:00Z", "value": False},
                                    {"time": "2026-11-18T12:00:00Z", "value": 2},
                                ],
                                "c1": True,
                                "c2": {"x": 1},
                            }
                        },
                    ]
                },
                "meta": {"warnings": ["junk", {"code": "w", "title": 5}], "elapsed": True},
            },
        ),
    )
    c, q = query(server)
    with c:
        res = q.aggregate_logs(sdk.DatadogCompute("count"))
    assert res.buckets[0] == sdk.DatadogBucket({"a": "x"}, {"c0": "str"})
    assert [p.value for p in res.buckets[1].timeseries("c0")] == [2.0]
    assert set(res.buckets[1].computes) == {"c0"}
    assert res.warnings == [sdk.DatadogWarning(code="w")] and res.elapsed_ms is None


def test_aggregate_errors(server: MockServer) -> None:
    server.defaults[AGGREGATE] = fixture_reply("aggregateLogs.401_missing_keys")
    c, q = query(server)
    with c, pytest.raises(sdk.AuthenticationError):
        q.aggregate_logs(sdk.DatadogCompute("count"))


def test_indexes_streams_labels(server: MockServer) -> None:
    server.defaults[INDEXES] = fixture_reply("listStreams.indexes")
    c, q = query(server)
    with c:
        assert q.list_indexes() == [
            sdk.DatadogLogIndex(
                "main", query="*", retention_days=15, daily_limit=300000000, is_rate_limited=False
            )
        ]
        assert q.list_streams() == ["main"]
        with pytest.raises(sdk.UnsupportedError):
            q.list_labels()
        server.defaults[INDEXES] = Reply(200, {"indexes": ["junk", {"name": "x", "filter": 1}]})
        assert q.list_indexes() == [sdk.DatadogLogIndex("x")]
    assert server.seen[0].method == "GET" and server.seen[0].headers["DD-APPLICATION-KEY"]


def test_search_and_wait_for_log(server: MockServer) -> None:
    c, q = query(server)
    with c:
        server.enqueue(SEARCH, event_reply(), event_reply("found it"))
        record = q.wait_for_log("found", timeout_s=5, interval_s=0.01)
        assert record.body == "found it"
        server.enqueue(SEARCH, event_reply("a"), event_reply("b"))
        assert q.wait_for_log("x", interval_s=0.01, predicate=lambda r: r.body == "b").body == "b"
        server.defaults[SEARCH] = event_reply()
        with pytest.raises(sdk.TimeoutError):
            q.wait_for_log("never", timeout_s=0.05, interval_s=0.01)
        server.defaults[SEARCH] = fixture_reply("searchLogs.403_invalid_keys")
        with pytest.raises(sdk.AuthenticationError):
            q.wait_for_log("x", timeout_s=5, interval_s=0.01)
        server.defaults[SEARCH] = fixture_reply("searchLogs.page1")
        start = datetime(2026, 11, 18, 12, tzinfo=timezone.utc)
        result = q.search("q", start=start, end=start, limit=5, cursor="cc")
        assert result.next_cursor and len(result.records) == 2
    sent = body_of(server.seen[-1])
    assert sent["sort"] == "-timestamp" and sent["page"] == {"limit": 5, "cursor": "cc"}
    assert sent["filter"]["from"] == sent["filter"]["to"] == "2026-11-18T12:00:00.000Z"


def test_tail_polls_and_stops_on_close(server: MockServer) -> None:
    server.defaults[SEARCH] = event_reply("tailed")
    c, q = query(server)
    got: list[sdk.LogRecord] = []
    errors: list[Exception] = []
    handle = q.tail("x", got.append, interval_s=0.02, on_error=errors.append)
    deadline = time.monotonic() + 5
    while not got and time.monotonic() < deadline:
        time.sleep(0.02)
    assert [r.body for r in got] == ["tailed"]
    assert body_of(server.seen[0])["sort"] == "timestamp"
    assert handle in c.resources._items  # type: ignore[attr-defined]
    c.close()
    assert not any(t.name.endswith("-tail") and t.is_alive() for t in threading.enumerate())
    assert errors == []
    assert DEFAULT_INTERVAL == 5.0


DEFAULT_INTERVAL = sdk.DEFAULT_POLL_INTERVAL_S


def test_query_without_registry() -> None:
    cfg = dd_config("http://unused")
    from grow_datadog_logs._datadog_http import SyncChannel

    channel = SyncChannel(cfg, cfg.api_url)
    q = sdk.DatadogLogsQuery(channel, cfg)
    handle = q.tail("x", lambda r: None, interval_s=10, start=datetime.now(timezone.utc))
    handle.stop()
    channel.close()
    with pytest.raises(sdk.ConfigError):
        sdk.DatadogLogsQuery(channel, dd_config("http://u", app_key=None))


# --------------------------------------------------------------------------- async


async def test_async_query(server: MockServer) -> None:
    server.defaults[INDEXES] = fixture_reply("listStreams.indexes")
    server.defaults[AGGREGATE] = fixture_reply("aggregateLogs.count_by_service")
    async with sdk.AsyncDatadogLogsClient(dd_config(server.url)) as c:
        q = c.query()
        server.enqueue(SEARCH, fixture_reply("searchLogs.page1"))
        page = await q.search_logs("x", limit=2, sort="timestamp", from_="now-1h", to="now")
        assert len(page.events) == 2 and page.next_cursor
        server.enqueue(SEARCH, event_reply("a", "b", after="c1"), event_reply("c", after="c2"))
        assert [e.message async for e in q.iterate_logs("x", limit=3, page_size=2)] == [
            "a",
            "b",
            "c",
        ]
        server.enqueue(SEARCH, event_reply("a", "b", after="c1"))
        assert len([e async for e in q.iterate_logs("x", limit=1)]) == 1
        server.enqueue(SEARCH, event_reply())
        assert [e async for e in q.iterate_logs("x")] == []
        server.enqueue(SEARCH, event_reply("a", after="s"), event_reply("b", after="s"))
        with pytest.raises(sdk.UnavailableError):
            _ = [e async for e in q.iterate_logs("x")]
        res = await q.aggregate_logs(sdk.DatadogCompute("count"), [sdk.DatadogGroupBy("service")])
        assert res.buckets[0].number("c0") == 3.0
        assert await q.list_streams() == ["main"]
        with pytest.raises(sdk.UnsupportedError):
            await q.list_labels()
        server.enqueue(SEARCH, event_reply(), event_reply("late"))
        assert (await q.wait_for_log("late", interval_s=0.01)).body == "late"
        server.enqueue(SEARCH, event_reply("s"))
        result = await q.search("s", order="asc", limit=1)
        assert [r.body for r in result.records] == ["s"]
        server.defaults[SEARCH] = event_reply("tail me")
        got: list[sdk.LogRecord] = []
        await q.tail("x", got.append, interval_s=0.02)
        for _ in range(250):
            if got:
                break
            await asyncio.sleep(0.02)
        assert [r.body for r in got] == ["tail me"]
    await c.aclose()


async def test_async_tail_stop_during_search_does_not_hang(server: MockServer) -> None:
    """Cancelling the tail while a search is in flight ends it (regression: stop() hung)."""
    server.defaults[SEARCH] = event_reply("slow", ts="2026-11-18T12:00:00Z")
    server.enqueue(SEARCH, Reply(200, {"data": []}, delay=1.0))
    errors: list[Exception] = []
    async with sdk.AsyncDatadogLogsClient(dd_config(server.url)) as c:
        handle = await c.query().tail("x", lambda r: None, interval_s=0.01, on_error=errors.append)
        for _ in range(100):
            if server.seen:
                break
            await asyncio.sleep(0.01)
        await asyncio.wait_for(handle.stop(), 5)
    assert errors == []


async def test_async_tail_reports_errors_and_keeps_polling(server: MockServer) -> None:
    server.enqueue(SEARCH, fixture_reply("searchLogs.400_invalid_query"))
    server.defaults[SEARCH] = event_reply("after error")
    errors: list[Exception] = []
    got: list[sdk.LogRecord] = []
    async with sdk.AsyncDatadogLogsClient(dd_config(server.url)) as c:
        await c.query().tail("x", got.append, interval_s=0.01, on_error=errors.append)
        for _ in range(300):
            if got:
                break
            await asyncio.sleep(0.01)
    assert isinstance(errors[0], sdk.ValidationError)
    assert [r.body for r in got] == ["after error"]
