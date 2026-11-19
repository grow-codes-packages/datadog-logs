"""Datadog Logs search, aggregate and indexes (service code; API.md section 6, DECISIONS 3, 5, 8).

:class:`DatadogLogsQuery` (sync) and :class:`AsyncDatadogLogsQuery` need the application key.
Besides the Datadog calls (``search_logs``, ``iterate_logs``, ``aggregate_logs``,
``list_indexes``) they implement the category :class:`LogQueryClient`: ``search``,
``wait_for_log``, ``tail`` (polling search; Live Tail has no public API), ``list_streams``
(index names, needs ``logs_read_config``) and ``list_labels`` (always ``UnsupportedError``).
"""

from __future__ import annotations

import asyncio
import dataclasses
from collections.abc import AsyncIterator, Callable, Iterator, Mapping, Sequence
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

from ._core import (
    CancelToken,
    ConfigError,
    Request,
    ResourceRegistry,
    UnavailableError,
    UnsupportedError,
    ValidationError,
)
from ._core.env import to_seconds
from ._datadog_http import HEADER_API_KEY, HEADER_APP_KEY, AsyncChannel, SyncChannel
from ._logging import (
    AsyncBaseLogQueryClient,
    AsyncPollingTail,
    BaseLogQueryClient,
    LogRecord,
    LogSearchResult,
    PollingTail,
    SearchOrder,
    async_wait_for_log,
    wait_for_log,
)
from .config import DatadogLogsConfig
from .mapping import DatadogLogEvent, event_to_log_record, parse_log_event

__all__ = [
    "AGGREGATE_PATH",
    "DEFAULT_ITERATOR_PAGE_SIZE",
    "DEFAULT_POLL_INTERVAL_S",
    "DEFAULT_SEARCH_FROM",
    "DEFAULT_SEARCH_TO",
    "INDEXES_PATH",
    "MAX_PAGE_LIMIT",
    "SEARCH_PATH",
    "AsyncDatadogLogsQuery",
    "DatadogAggregateResult",
    "DatadogBucket",
    "DatadogCompute",
    "DatadogGroupBy",
    "DatadogGroupBySort",
    "DatadogLogIndex",
    "DatadogLogSearchResult",
    "DatadogLogsPage",
    "DatadogLogsQuery",
    "DatadogTime",
    "DatadogTimeseriesPoint",
    "DatadogWarning",
    "LogsSort",
    "StorageTier",
    "format_datadog_time",
]

#: Search path (``POST``).
SEARCH_PATH = "/api/v2/logs/events/search"
#: Aggregate path (``POST``).
AGGREGATE_PATH = "/api/v2/logs/analytics/aggregate"
#: Indexes path (``GET``).
INDEXES_PATH = "/api/v1/logs/config/indexes"
#: Largest ``page.limit`` accepted by the search API.
MAX_PAGE_LIMIT = 5000
#: Page size used by ``iterate_logs`` when none is given.
DEFAULT_ITERATOR_PAGE_SIZE = 1000
#: Default poll interval of ``tail`` and ``wait_for_log`` (the search API is rate limited).
DEFAULT_POLL_INTERVAL_S = 5.0
#: Default window start.
DEFAULT_SEARCH_FROM = "now-15m"
#: Default window end.
DEFAULT_SEARCH_TO = "now"

#: A ``datetime``, epoch milliseconds or a string (ISO-8601 or date math such as ``now-15m``).
DatadogTime = datetime | int | float | str
#: ``timestamp`` (oldest first) or ``-timestamp`` (newest first).
LogsSort = Literal["timestamp", "-timestamp"]
#: Where the search runs.
StorageTier = Literal["indexes", "online-archives", "flex"]


def format_datadog_time(value: DatadogTime) -> str:
    """Convert a :data:`DatadogTime` to the API form (ISO-8601 UTC with milliseconds).

    Strings (ISO-8601 or date math) are sent as given (trimmed).
    """
    if isinstance(value, bool):
        raise ValidationError("logs.query: invalid time")
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        utc = value.astimezone(timezone.utc)
        return utc.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc.microsecond // 1000:03d}Z"
    if isinstance(value, (int, float)):
        try:
            when = datetime.fromtimestamp(value / 1000.0, tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            raise ValidationError("logs.query: invalid epoch time") from None
        return format_datadog_time(when)
    text = str(value).strip()
    if not text:
        raise ValidationError("logs.query: time must not be empty")
    return text


@dataclasses.dataclass(frozen=True)
class DatadogWarning:
    """A non-fatal warning of a query (results may be partial)."""

    code: str | None = None
    title: str | None = None
    detail: str | None = None


@dataclasses.dataclass(frozen=True)
class DatadogLogsPage:
    """One page of :meth:`DatadogLogsQuery.search_logs`."""

    #: Events as returned by Datadog (flattened).
    events: list[DatadogLogEvent]
    #: Cursor of the next page; ``None`` on the last page.
    next_cursor: str | None = None
    #: ``done`` or ``timeout`` (partial results).
    status: str | None = None
    #: Non-fatal warnings (e.g. ``unknown_index``).
    warnings: list[DatadogWarning] = dataclasses.field(default_factory=list)
    #: ``meta.request_id``.
    request_id: str | None = None
    #: Server time in milliseconds (``meta.elapsed``).
    elapsed_ms: float | None = None

    @property
    def records(self) -> list[LogRecord]:
        """The events normalized to :class:`LogRecord`."""
        return [event_to_log_record(e) for e in self.events]


@dataclasses.dataclass(frozen=True)
class DatadogLogSearchResult(LogSearchResult):
    """Result of :meth:`DatadogLogsQuery.search`: the category result plus Datadog metadata."""

    #: Non-fatal warnings (results may be partial).
    warnings: list[DatadogWarning] = dataclasses.field(default_factory=list)
    #: ``done`` or ``timeout``.
    status: str | None = None
    #: ``meta.request_id``.
    request_id: str | None = None


@dataclasses.dataclass(frozen=True)
class DatadogCompute:
    """One computation of ``aggregate_logs``; results are keyed ``c0``, ``c1`` … in order."""

    #: count, cardinality, pc75, pc90, pc95, pc98, pc99, sum, min, max, avg or median.
    aggregation: str
    #: ``total`` (default) or ``timeseries``.
    type: str = "total"
    #: Time bucket for ``timeseries``, e.g. ``5m``.
    interval: str | None = None
    #: Measure for non-count aggregations, e.g. ``@duration``.
    metric: str | None = None


@dataclasses.dataclass(frozen=True)
class DatadogGroupBySort:
    """Sort of a group-by."""

    aggregation: str | None = None
    #: ``asc`` or ``desc``.
    order: str | None = None
    #: ``alphabetical`` or ``measure``.
    type: str | None = None
    metric: str | None = None


@dataclasses.dataclass(frozen=True)
class DatadogGroupBy:
    """One group-by facet of ``aggregate_logs``."""

    #: Facet, e.g. ``service`` or ``@http.status_code``.
    facet: str
    #: Maximum buckets (Datadog default 10).
    limit: int | None = None
    sort: DatadogGroupBySort | Mapping[str, Any] | None = None
    #: Value used for logs without the facet.
    missing: str | int | float | None = None
    #: Adds a total bucket (``True`` or its label).
    total: bool | str | int | float | None = None
    #: Numeric histogram buckets: ``{"interval": …, "min": …, "max": …}``.
    histogram: Mapping[str, float] | None = None


@dataclasses.dataclass(frozen=True)
class DatadogTimeseriesPoint:
    """One point of a timeseries compute."""

    time: datetime
    value: float


ComputeValue = float | str | list[DatadogTimeseriesPoint]


@dataclasses.dataclass(frozen=True)
class DatadogBucket:
    """One bucket of an aggregation."""

    #: Facet values of the bucket (``{}`` without group-by).
    by: dict[str, str | float]
    #: Results keyed ``c0``, ``c1`` … in compute order.
    computes: dict[str, ComputeValue]

    def number(self, key: str) -> float | None:
        """The numeric value of compute ``key`` (``None`` when missing or not a number)."""
        value = self.computes.get(key)
        return float(value) if isinstance(value, (int, float)) else None

    def timeseries(self, key: str) -> list[DatadogTimeseriesPoint]:
        """The timeseries of compute ``key`` (``[]`` when missing or not a timeseries)."""
        value = self.computes.get(key)
        return list(value) if isinstance(value, list) else []


@dataclasses.dataclass(frozen=True)
class DatadogAggregateResult:
    """Result of ``aggregate_logs``."""

    buckets: list[DatadogBucket]
    next_cursor: str | None = None
    status: str | None = None
    warnings: list[DatadogWarning] = dataclasses.field(default_factory=list)
    request_id: str | None = None
    elapsed_ms: float | None = None


@dataclasses.dataclass(frozen=True)
class DatadogLogIndex:
    """One Datadog log index."""

    name: str
    #: Index filter query.
    query: str | None = None
    retention_days: int | None = None
    daily_limit: int | None = None
    is_rate_limited: bool | None = None


# --------------------------------------------------------------------------- pure helpers


def _check_limit(value: int | None, op: str, name: str = "limit") -> None:
    if value is None:
        return
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= MAX_PAGE_LIMIT:
        raise ValidationError(f"{op}: {name} must be an integer 1..{MAX_PAGE_LIMIT}")


def _filter_body(
    query: str | None,
    from_: DatadogTime | None,
    to: DatadogTime | None,
    indexes: Sequence[str] | None,
    storage_tier: str | None,
) -> dict[str, Any]:
    if isinstance(indexes, str):
        indexes = [indexes]
    return {
        "query": query if query is not None and query.strip() else "*",
        "from": format_datadog_time(from_ if from_ is not None else DEFAULT_SEARCH_FROM),
        "to": format_datadog_time(to if to is not None else DEFAULT_SEARCH_TO),
        "indexes": list(indexes) if indexes else ["*"],
        "storage_tier": storage_tier or "indexes",
    }


def _search_body(
    op: str,
    query: str | None,
    from_: DatadogTime | None,
    to: DatadogTime | None,
    indexes: Sequence[str] | None,
    storage_tier: str | None,
    limit: int | None,
    sort: str | None,
    cursor: str | None,
) -> dict[str, Any]:
    _check_limit(limit, op)
    sort = sort or "-timestamp"
    if sort not in ("timestamp", "-timestamp"):
        raise ValidationError(f'{op}: sort must be "timestamp" or "-timestamp"')
    page: dict[str, Any] = {"limit": limit if limit is not None else 100}
    if cursor:
        page["cursor"] = cursor
    return {
        "filter": _filter_body(query, from_, to, indexes, storage_tier),
        "sort": sort,
        "page": page,
    }


def _meta(body: Mapping[str, Any], header_request_id: str | None) -> dict[str, Any]:
    meta = body.get("meta")
    meta = meta if isinstance(meta, Mapping) else {}
    page = meta.get("page")
    page = page if isinstance(page, Mapping) else {}
    after = page.get("after")
    warnings: list[DatadogWarning] = []
    raw_warnings = meta.get("warnings")
    if isinstance(raw_warnings, list):
        for w in raw_warnings:
            if isinstance(w, Mapping):
                warnings.append(
                    DatadogWarning(
                        **{
                            k: w[k]
                            for k in ("code", "title", "detail")
                            if isinstance(w.get(k), str)
                        }
                    )
                )
    request_id = meta.get("request_id")
    elapsed = meta.get("elapsed")
    status = meta.get("status")
    return {
        "next_cursor": after if isinstance(after, str) and after else None,
        "status": status if isinstance(status, str) else None,
        "warnings": warnings,
        "request_id": request_id if isinstance(request_id, str) else header_request_id,
        "elapsed_ms": float(elapsed)
        if isinstance(elapsed, (int, float)) and not isinstance(elapsed, bool)
        else None,
    }


def _json_object(op: str, resp: Any) -> Mapping[str, Any]:
    try:
        body = resp.json()
    except ValidationError:
        body = None
    if not isinstance(body, Mapping):
        raise UnavailableError(
            f"{op}: unexpected response (no JSON object)", retryable=False, http_status=resp.status
        )
    return body


def _parse_page(op: str, resp: Any) -> DatadogLogsPage:
    body = _json_object(op, resp)
    data = body.get("data")
    if not isinstance(data, list):
        raise UnavailableError(
            f"{op}: unexpected response (no data array)", retryable=False, http_status=resp.status
        )
    events = [e for e in (parse_log_event(item) for item in data) if e is not None]
    return DatadogLogsPage(events=events, **_meta(body, resp.request_id))


def _compute_wire(op: str, compute: DatadogCompute | Mapping[str, Any]) -> dict[str, Any]:
    c = compute if isinstance(compute, DatadogCompute) else DatadogCompute(**dict(compute))
    if not isinstance(c.aggregation, str) or not c.aggregation.strip():
        raise ValidationError(f"{op}: compute.aggregation is required")
    out: dict[str, Any] = {"aggregation": c.aggregation, "type": c.type or "total"}
    if c.interval is not None:
        out["interval"] = c.interval
    if c.metric is not None:
        out["metric"] = c.metric
    return out


def _group_by_wire(op: str, group: DatadogGroupBy | Mapping[str, Any]) -> dict[str, Any]:
    g = group if isinstance(group, DatadogGroupBy) else DatadogGroupBy(**dict(group))
    if not isinstance(g.facet, str) or not g.facet.strip():
        raise ValidationError(f"{op}: group_by.facet is required")
    out: dict[str, Any] = {"facet": g.facet}
    if g.limit is not None:
        out["limit"] = g.limit
    if g.sort is not None:
        sort = (
            {k: v for k, v in dataclasses.asdict(g.sort).items() if v is not None}
            if isinstance(g.sort, DatadogGroupBySort)
            else dict(g.sort)
        )
        out["sort"] = sort
    if g.missing is not None:
        out["missing"] = g.missing
    if g.total is not None:
        out["total"] = g.total
    if g.histogram is not None:
        out["histogram"] = dict(g.histogram)
    return out


def _aggregate_body(
    op: str,
    compute: DatadogCompute | Mapping[str, Any] | Sequence[DatadogCompute | Mapping[str, Any]],
    group_by: Sequence[DatadogGroupBy | Mapping[str, Any]],
    filter_: dict[str, Any],
    cursor: str | None,
) -> dict[str, Any]:
    computes = [compute] if isinstance(compute, (DatadogCompute, Mapping)) else list(compute)
    if not computes:
        raise ValidationError(f"{op}: compute must not be empty")
    body: dict[str, Any] = {"compute": [_compute_wire(op, c) for c in computes], "filter": filter_}
    groups = [_group_by_wire(op, g) for g in group_by]
    if groups:
        body["group_by"] = groups
    if cursor:
        body["page"] = {"cursor": cursor}
    return body


def _compute_value(value: Any) -> ComputeValue | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float, str)):
        return value
    if not isinstance(value, list):
        return None
    points: list[DatadogTimeseriesPoint] = []
    for p in value:
        if not isinstance(p, Mapping):
            continue
        t, v = p.get("time"), p.get("value")
        if isinstance(t, str) and isinstance(v, (int, float)) and not isinstance(v, bool):
            try:
                when = datetime.fromisoformat(t.replace("Z", "+00:00"))
            except ValueError:
                continue
            points.append(DatadogTimeseriesPoint(when, float(v)))
    return points


def _parse_aggregate(op: str, resp: Any) -> DatadogAggregateResult:
    body = _json_object(op, resp)
    data = body.get("data")
    raw = data.get("buckets") if isinstance(data, Mapping) else None
    if not isinstance(raw, list):
        raise UnavailableError(
            f"{op}: unexpected response (no data.buckets)", retryable=False, http_status=resp.status
        )
    buckets: list[DatadogBucket] = []
    for b in raw:
        if not isinstance(b, Mapping):
            continue
        by: dict[str, str | float] = {}
        if isinstance(b.get("by"), Mapping):
            for k, v in b["by"].items():
                if isinstance(v, (str, int, float)) and not isinstance(v, bool):
                    by[str(k)] = v
        computes: dict[str, ComputeValue] = {}
        if isinstance(b.get("computes"), Mapping):
            for k, v in b["computes"].items():
                parsed = _compute_value(v)
                if parsed is not None:
                    computes[str(k)] = parsed
        buckets.append(DatadogBucket(by, computes))
    return DatadogAggregateResult(buckets=buckets, **_meta(body, resp.request_id))


def _parse_indexes(op: str, resp: Any) -> list[DatadogLogIndex]:
    body = _json_object(op, resp)
    raw = body.get("indexes")
    if not isinstance(raw, list):
        raise UnavailableError(
            f"{op}: unexpected response (no indexes array)",
            retryable=False,
            http_status=resp.status,
        )
    out: list[DatadogLogIndex] = []
    for i in raw:
        if not isinstance(i, Mapping) or not isinstance(i.get("name"), str):
            continue
        flt = i.get("filter")
        query = flt.get("query") if isinstance(flt, Mapping) else None

        def _int(key: str, item: Mapping[str, Any] = i) -> int | None:
            v = item.get(key)
            return int(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None

        limited = i.get("is_rate_limited")
        out.append(
            DatadogLogIndex(
                name=i["name"],
                query=query if isinstance(query, str) else None,
                retention_days=_int("num_retention_days"),
                daily_limit=_int("daily_limit"),
                is_rate_limited=limited if isinstance(limited, bool) else None,
            )
        )
    return out


def _search_result(page: DatadogLogsPage) -> DatadogLogSearchResult:
    return DatadogLogSearchResult(
        records=page.records,
        next_cursor=page.next_cursor,
        warnings=page.warnings,
        status=page.status,
        request_id=page.request_id,
    )


def _iter_page_size(op: str, limit: int | None, page_size: int | None) -> int:
    if limit is not None and (isinstance(limit, bool) or not isinstance(limit, int) or limit < 1):
        raise ValidationError(f"{op}: limit must be a positive integer")
    _check_limit(page_size, op, "page_size")
    if page_size is not None:
        return page_size
    return min(
        limit if limit is not None else DEFAULT_ITERATOR_PAGE_SIZE, DEFAULT_ITERATOR_PAGE_SIZE
    )


def _loop_guard(op: str, seen: set[str], cursor: str) -> None:
    if cursor in seen:
        raise UnavailableError(f"{op}: the service repeated a page cursor", retryable=False)
    seen.add(cursor)


class _AsyncPollingTail(AsyncPollingTail):
    """:class:`AsyncPollingTail` that lets task cancellation through.

    The SDK's ``CancelledError`` (raised when the task is cancelled during a search) is also an
    ``Exception``; the generic tail would report it to ``on_error`` and keep polling, so
    :meth:`stop` would wait forever. Cancellation always ends this loop.
    """

    async def _run(self) -> None:
        while True:
            try:
                await self.poll_once()
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - reported to on_error, polling continues
                if self._on_error is not None:
                    self._on_error(exc)
            await asyncio.sleep(self._interval)


class _QueryBase:
    def __init__(self, config: DatadogLogsConfig) -> None:
        if config.app_key is None:
            raise ConfigError(
                "config: app_key is required for the query API "
                "(GROW_DATADOG_LOGS_APP_KEY or DD_APP_KEY)"
            )
        self.config = config

    def _headers(self) -> dict[str, str]:
        api_key = self.config.api_key.reveal() if self.config.api_key else ""
        app_key = self.config.app_key.reveal() if self.config.app_key else ""
        return {HEADER_API_KEY: api_key, HEADER_APP_KEY: app_key}

    def _post(self, path: str, op: str, body: dict[str, Any]) -> Request:
        # read-only POSTs: safe to retry on 5xx
        return Request(
            "POST", path, operation=op, json=body, headers=self._headers(), idempotent=True
        )

    def _get(self, path: str, op: str) -> Request:
        return Request("GET", path, operation=op, headers=self._headers())


# --------------------------------------------------------------------------- sync


class DatadogLogsQuery(_QueryBase, BaseLogQueryClient):
    """Synchronous Datadog Logs query client (needs ``app_key``).

    Create it with :meth:`DatadogLogsClient.query`. Every I/O method accepts ``cancel=``.
    """

    def __init__(
        self,
        channel: SyncChannel,
        config: DatadogLogsConfig,
        resources: ResourceRegistry | None = None,
    ) -> None:
        super().__init__(config)
        self._channel = channel
        self._resources = resources

    def search_logs(
        self,
        query: str | None = None,
        *,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        sort: LogsSort | None = None,
        indexes: Sequence[str] | None = None,
        storage_tier: StorageTier | None = None,
        cursor: str | None = None,
        cancel: CancelToken | None = None,
    ) -> DatadogLogsPage:
        """One page of ``POST /api/v2/logs/events/search``.

        ``from_``/``to`` default to ``now-15m``/``now``, ``limit`` (1..5000) to 100, ``sort`` to
        ``-timestamp``, ``indexes`` to ``["*"]``. Pass :attr:`DatadogLogsPage.next_cursor` back as
        ``cursor`` (with the same filter and sort) for the next page. A 200 without a ``data``
        array raises :class:`UnavailableError`.

        >>> query.search_logs('service:checkout "run-7f3a"', from_="now-1h")  # doctest: +SKIP
        """
        op = "logs.search_logs"
        body = _search_body(op, query, from_, to, indexes, storage_tier, limit, sort, cursor)
        return _parse_page(op, self._channel.request(self._post(SEARCH_PATH, op, body), cancel))

    def iterate_logs(
        self,
        query: str | None = None,
        *,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        page_size: int | None = None,
        sort: LogsSort | None = None,
        indexes: Sequence[str] | None = None,
        storage_tier: StorageTier | None = None,
        cursor: str | None = None,
        cancel: CancelToken | None = None,
    ) -> Iterator[DatadogLogEvent]:
        """Iterate over every matching event across pages (cursor pagination).

        ``limit`` caps the total number of events (default: all), ``page_size`` the events per
        request (default ``min(limit, 1000)``). A repeated cursor raises
        :class:`UnavailableError` (protocol loop guard).
        """
        op = "logs.iterate_logs"
        size = _iter_page_size(op, limit, page_size)
        remaining = limit
        seen: set[str] = set()
        while remaining is None or remaining > 0:
            page = self.search_logs(
                query,
                from_=from_,
                to=to,
                limit=size if remaining is None else min(size, remaining),
                sort=sort,
                indexes=indexes,
                storage_tier=storage_tier,
                cursor=cursor,
                cancel=cancel,
            )
            for event in page.events:
                if remaining is not None:
                    if remaining <= 0:
                        return
                    remaining -= 1
                yield event
            if page.next_cursor is None or not page.events:
                return
            _loop_guard(op, seen, page.next_cursor)
            cursor = page.next_cursor

    def aggregate_logs(
        self,
        compute: DatadogCompute | Mapping[str, Any] | Sequence[DatadogCompute | Mapping[str, Any]],
        group_by: Sequence[DatadogGroupBy | Mapping[str, Any]] = (),
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        indexes: Sequence[str] | None = None,
        storage_tier: StorageTier | None = None,
        cursor: str | None = None,
        cancel: CancelToken | None = None,
    ) -> DatadogAggregateResult:
        """``POST /api/v2/logs/analytics/aggregate``.

        Computes ``compute`` (one or a list; results keyed ``c0``, ``c1`` …) over the logs
        matching the filter, optionally grouped by facets.

        >>> q.aggregate_logs(DatadogCompute("count"), [DatadogGroupBy("service")]) # doctest: +SKIP
        """
        op = "logs.aggregate_logs"
        flt = _filter_body(query, from_, to, indexes, storage_tier)
        body = _aggregate_body(op, compute, group_by, flt, cursor)
        resp = self._channel.request(self._post(AGGREGATE_PATH, op, body), cancel)
        return _parse_aggregate(op, resp)

    def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = 100,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> DatadogLogSearchResult:
        """Category search: one page of :meth:`search_logs`.

        ``start``/``end`` -> ``from``/``to``, ``order`` -> ``sort``, ``cursor`` -> ``page.cursor``.
        ``total`` is never set (Datadog returns none).
        """
        page = self.search_logs(
            query,
            from_=start,
            to=end,
            limit=limit,
            sort="timestamp" if order == "asc" else "-timestamp",
            cursor=cursor,
        )
        return _search_result(page)

    def list_indexes(self, *, cancel: CancelToken | None = None) -> list[DatadogLogIndex]:
        """Log indexes from ``GET /api/v1/logs/config/indexes`` (needs ``logs_read_config``)."""
        op = "logs.list_indexes"
        return _parse_indexes(op, self._channel.request(self._get(INDEXES_PATH, op), cancel))

    def list_streams(self) -> list[str]:
        """Index names (see :meth:`list_indexes`)."""
        return [i.name for i in self.list_indexes()]

    def list_labels(self) -> list[str]:
        """Datadog has no public API that lists log attributes: raises :class:`UnsupportedError`."""
        raise UnsupportedError("logs.list_labels: Datadog has no API that lists log attributes")

    def tail(
        self,
        query: str,
        on_record: Callable[[LogRecord], None],
        *,
        interval_s: float | timedelta = DEFAULT_POLL_INTERVAL_S,
        start: datetime | None = None,
        on_error: Callable[[Exception], None] | None = None,
    ) -> PollingTail:
        """Polling tail over :meth:`search` (Datadog Live Tail has no public API).

        Polls every ``interval_s`` (default 5 s, the search API is rate limited) for records from
        ``start`` (default: now, like the other languages). :meth:`DatadogLogsClient.close` stops
        it.
        """
        handle = PollingTail(
            self,
            query,
            on_record,
            interval_s=to_seconds(interval_s),
            start=start if start is not None else datetime.now(timezone.utc),
            on_error=on_error,
        )
        if self._resources is not None:
            self._resources.register(handle)
        return handle

    def wait_for_log(
        self,
        query: str,
        *,
        timeout_s: float | timedelta = 30.0,
        interval_s: float | timedelta = DEFAULT_POLL_INTERVAL_S,
        predicate: Callable[[LogRecord], bool] | None = None,
    ) -> LogRecord:
        """Poll :meth:`search` until a record matches (default interval 5 s, timeout 30 s).

        Authentication and validation errors propagate at once; :class:`TimeoutError` at the
        deadline.
        """
        return wait_for_log(
            self, query, timeout_s=timeout_s, interval_s=interval_s, predicate=predicate
        )


# --------------------------------------------------------------------------- async


class AsyncDatadogLogsQuery(_QueryBase, AsyncBaseLogQueryClient):
    """Async :class:`DatadogLogsQuery`; create it with :meth:`AsyncDatadogLogsClient.query`."""

    def __init__(self, channel: AsyncChannel, config: DatadogLogsConfig) -> None:
        super().__init__(config)
        self._channel = channel
        self._tails: list[AsyncPollingTail] = []

    async def search_logs(
        self,
        query: str | None = None,
        *,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        sort: LogsSort | None = None,
        indexes: Sequence[str] | None = None,
        storage_tier: StorageTier | None = None,
        cursor: str | None = None,
    ) -> DatadogLogsPage:
        """One page of ``POST /api/v2/logs/events/search`` (see the sync client)."""
        op = "logs.search_logs"
        body = _search_body(op, query, from_, to, indexes, storage_tier, limit, sort, cursor)
        return _parse_page(op, await self._channel.request(self._post(SEARCH_PATH, op, body)))

    async def iterate_logs(
        self,
        query: str | None = None,
        *,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        page_size: int | None = None,
        sort: LogsSort | None = None,
        indexes: Sequence[str] | None = None,
        storage_tier: StorageTier | None = None,
        cursor: str | None = None,
    ) -> AsyncIterator[DatadogLogEvent]:
        """Async iterator over every matching event across pages (see the sync client)."""
        op = "logs.iterate_logs"
        size = _iter_page_size(op, limit, page_size)
        remaining = limit
        seen: set[str] = set()
        while remaining is None or remaining > 0:
            page = await self.search_logs(
                query,
                from_=from_,
                to=to,
                limit=size if remaining is None else min(size, remaining),
                sort=sort,
                indexes=indexes,
                storage_tier=storage_tier,
                cursor=cursor,
            )
            for event in page.events:
                if remaining is not None:
                    if remaining <= 0:
                        return
                    remaining -= 1
                yield event
            if page.next_cursor is None or not page.events:
                return
            _loop_guard(op, seen, page.next_cursor)
            cursor = page.next_cursor

    async def aggregate_logs(
        self,
        compute: DatadogCompute | Mapping[str, Any] | Sequence[DatadogCompute | Mapping[str, Any]],
        group_by: Sequence[DatadogGroupBy | Mapping[str, Any]] = (),
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        indexes: Sequence[str] | None = None,
        storage_tier: StorageTier | None = None,
        cursor: str | None = None,
    ) -> DatadogAggregateResult:
        """``POST /api/v2/logs/analytics/aggregate`` (see the sync client)."""
        op = "logs.aggregate_logs"
        flt = _filter_body(query, from_, to, indexes, storage_tier)
        body = _aggregate_body(op, compute, group_by, flt, cursor)
        return _parse_aggregate(
            op, await self._channel.request(self._post(AGGREGATE_PATH, op, body))
        )

    async def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = 100,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> DatadogLogSearchResult:
        """Category search: one page of :meth:`search_logs`."""
        page = await self.search_logs(
            query,
            from_=start,
            to=end,
            limit=limit,
            sort="timestamp" if order == "asc" else "-timestamp",
            cursor=cursor,
        )
        return _search_result(page)

    async def list_indexes(self) -> list[DatadogLogIndex]:
        """Log indexes from ``GET /api/v1/logs/config/indexes`` (needs ``logs_read_config``)."""
        op = "logs.list_indexes"
        return _parse_indexes(op, await self._channel.request(self._get(INDEXES_PATH, op)))

    async def list_streams(self) -> list[str]:
        """Index names (see :meth:`list_indexes`)."""
        return [i.name for i in await self.list_indexes()]

    async def list_labels(self) -> list[str]:
        """Always raises :class:`UnsupportedError` (no Datadog API lists log attributes)."""
        raise UnsupportedError("logs.list_labels: Datadog has no API that lists log attributes")

    async def tail(
        self,
        query: str,
        on_record: Callable[[LogRecord], None],
        *,
        interval_s: float | timedelta = DEFAULT_POLL_INTERVAL_S,
        start: datetime | None = None,
        on_error: Callable[[Exception], None] | None = None,
    ) -> AsyncPollingTail:
        """Polling tail over :meth:`search` on the running loop (default interval 5 s).

        Records from ``start`` (default: now). :meth:`AsyncDatadogLogsClient.aclose` stops it.
        """
        handle = _AsyncPollingTail(
            self,
            query,
            on_record,
            interval_s=to_seconds(interval_s),
            start=start if start is not None else datetime.now(timezone.utc),
            on_error=on_error,
        )
        self._tails.append(handle)
        return handle

    async def wait_for_log(
        self,
        query: str,
        *,
        timeout_s: float | timedelta = 30.0,
        interval_s: float | timedelta = DEFAULT_POLL_INTERVAL_S,
        predicate: Callable[[LogRecord], bool] | None = None,
    ) -> LogRecord:
        """Poll :meth:`search` until a record matches (default interval 5 s, timeout 30 s)."""
        return await async_wait_for_log(
            self, query, timeout_s=timeout_s, interval_s=interval_s, predicate=predicate
        )

    async def stop_tails(self) -> None:
        """Stop every tail started by this client (called by the client's ``aclose``)."""
        tails, self._tails = self._tails, []
        for tail in tails:
            await tail.stop()
