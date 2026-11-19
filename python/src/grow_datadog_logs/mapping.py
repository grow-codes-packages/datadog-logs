"""``LogRecord`` <-> Datadog JSON (service code; API.md 5.2 and 6.1, DECISIONS 6, 7, 9)."""

from __future__ import annotations

import dataclasses
import json
import re
from collections.abc import Mapping, Sequence
from datetime import datetime, timedelta, timezone
from typing import Any

from ._core import ValidationError
from ._logging import AttrValue, LogRecord, Severity, parse_timestamp, to_attr_value

__all__ = [
    "MAX_ENTRIES_PER_REQUEST",
    "MAX_LOG_BYTES",
    "MAX_PAYLOAD_BYTES",
    "RESERVED_KEYS",
    "RESERVED_PREFIX",
    "TRUNCATED_ATTRIBUTE",
    "DatadogLogEvent",
    "EntryDefaults",
    "SerializedEntry",
    "build_ddtags",
    "datadog_id_to_hex",
    "datadog_status",
    "event_to_log_record",
    "parse_log_event",
    "serialize_entry",
    "severity_from_status",
    "span_id_to_datadog",
    "to_datadog_entry",
    "trace_id_to_datadog",
]

#: Maximum entries in one intake request (Datadog limit).
MAX_ENTRIES_PER_REQUEST = 1000
#: Maximum uncompressed intake payload in bytes, brackets and commas included (Datadog: 5 MB).
MAX_PAYLOAD_BYTES = 5_000_000
#: Maximum serialized size of one log entry in bytes (Datadog: 1 MB).
MAX_LOG_BYTES = 1_000_000
#: Attribute set to ``True`` on an entry whose message was truncated by the SDK.
TRUNCATED_ATTRIBUTE = "grow.truncated"
#: Prefix given to user attributes whose name is reserved (DECISIONS 7).
RESERVED_PREFIX = "attr."

#: Top-level keys the SDK writes itself; a user attribute with one of these names is sent as
#: ``attr.<name>``.
RESERVED_KEYS: tuple[str, ...] = (
    "message",
    "timestamp",
    "status",
    "service",
    "hostname",
    "ddsource",
    "ddtags",
    "dd.trace_id",
    "dd.span_id",
    "otel.trace_id",
    "otel.span_id",
    TRUNCATED_ATTRIBUTE,
)

_HEX = re.compile(r"^[0-9a-f]+$")
_DECIMAL = re.compile(r"^\d+$")
_TAG_CLEAN = re.compile(r"[\s,]+")
_MAX_U64 = (1 << 64) - 1
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


@dataclasses.dataclass(frozen=True)
class EntryDefaults:
    """Values used when a record's resource does not carry them (from the client config)."""

    service: str | None = None
    hostname: str | None = None
    env: str | None = None
    version: str | None = None
    tags: Sequence[str] = ()
    source: str | None = None


def trace_id_to_datadog(trace_id: str | None) -> str | None:
    """Datadog trace-id form (DECISIONS 6): decimal string of the lower 64 bits of a 32-hex id.

    Returns ``None`` for invalid input.

    >>> trace_id_to_datadog("4bf92f3577b34da6a3ce929d0e0e4736")
    '11803532876627986230'
    """
    if trace_id is None:
        return None
    text = trace_id.strip().lower()
    if not text or len(text) > 32 or not _HEX.match(text):
        return None
    return str(int(text[-16:], 16))


def span_id_to_datadog(span_id: str | None) -> str | None:
    """Datadog span-id form: decimal string of a 16-hex OpenTelemetry span id."""
    if span_id is None:
        return None
    text = span_id.strip().lower()
    if not text or len(text) > 16 or not _HEX.match(text):
        return None
    return str(int(text, 16))


def datadog_id_to_hex(value: Any, length: int) -> str | None:
    """Convert a Datadog id (decimal of up to 64 bits, or lower hex) to hex padded to ``length``.

    Returns ``None`` for invalid or all-zero ids.
    """
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value > 0:
        text = str(value)
    elif isinstance(value, str):
        text = value.strip().lower()
    else:
        return None
    if _DECIMAL.match(text) and len(text) <= 20 and int(text) <= _MAX_U64:
        hex_text = format(int(text), "x")
    elif _HEX.match(text) and len(text) <= length:
        hex_text = text
    else:
        return None
    padded = hex_text.rjust(length, "0")
    return None if set(padded) == {"0"} else padded


def datadog_status(record: LogRecord) -> str:
    """Datadog ``status`` of a record: its severity text lower-cased, else the severity name."""
    text = record.severity_text.strip().lower()
    return text or Severity(record.severity).name.lower()


_SEVERITY_BY_STATUS: dict[str, Severity] = {
    "emerg": Severity.FATAL,
    "emergency": Severity.FATAL,
    "alert": Severity.FATAL,
    "crit": Severity.FATAL,
    "critical": Severity.FATAL,
    "fatal": Severity.FATAL,
    "panic": Severity.FATAL,
    "err": Severity.ERROR,
    "error": Severity.ERROR,
    "warn": Severity.WARN,
    "warning": Severity.WARN,
    "notice": Severity.INFO,
    "info": Severity.INFO,
    "information": Severity.INFO,
    "informational": Severity.INFO,
    "ok": Severity.INFO,
    "success": Severity.INFO,
    "debug": Severity.DEBUG,
    "trace": Severity.TRACE,
}


def severity_from_status(status: str | None) -> Severity:
    """OpenTelemetry severity of a Datadog ``status`` (unknown values map to ``INFO``)."""
    return _SEVERITY_BY_STATUS.get((status or "").strip().lower(), Severity.INFO)


def _string_attr(value: AttrValue | None) -> str | None:
    if value is None:
        return None
    text = value if isinstance(value, str) else _dumps(value)
    return text if text.strip() else None


def build_ddtags(env: str | None, version: str | None, tags: Sequence[str] = ()) -> str | None:
    """The ``ddtags`` value: ``env:``, ``version:``, then ``tags``.

    Comma-joined, de-duplicated, whitespace and commas inside a tag replaced by ``_``. ``None``
    when there is no tag.
    """
    out: list[str] = []

    def add(tag: str) -> None:
        clean = _TAG_CLEAN.sub("_", tag)
        if clean and clean not in out:
            out.append(clean)

    if env is not None:
        add(f"env:{env}")
    if version is not None:
        add(f"version:{version}")
    for tag in tags:
        add(tag)
    return ",".join(out) if out else None


def _epoch_ms(value: datetime) -> int:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return (value - _EPOCH) // timedelta(milliseconds=1)


def to_datadog_entry(record: LogRecord, defaults: EntryDefaults | None = None) -> dict[str, Any]:
    """Convert a :class:`LogRecord` into a Datadog intake entry (API.md 5.2).

    ``message``, ``timestamp`` (epoch ms), ``status``, ``service``, ``hostname``, ``ddsource``,
    ``ddtags``, ``dd.trace_id`` / ``dd.span_id`` (decimal, DECISIONS 6) plus ``otel.trace_id`` /
    ``otel.span_id`` (lower hex, lossless), then the record attributes (other resource keys are
    not sent). User attributes named like a reserved key are prefixed with ``attr.``.
    """
    d = defaults or EntryDefaults()
    resource = record.resource
    entry: dict[str, Any] = {
        "message": record.body,
        "timestamp": _epoch_ms(record.timestamp),
        "status": datadog_status(record),
    }
    service = _string_attr(resource.get("service.name")) or d.service
    if service is not None:
        entry["service"] = service
    hostname = _string_attr(resource.get("host.name")) or d.hostname
    if hostname is not None:
        entry["hostname"] = hostname
    if d.source is not None:
        entry["ddsource"] = d.source
    ddtags = build_ddtags(
        _string_attr(resource.get("deployment.environment")) or d.env,
        _string_attr(resource.get("service.version")) or d.version,
        d.tags,
    )
    if ddtags is not None:
        entry["ddtags"] = ddtags
    dd_trace = trace_id_to_datadog(record.trace_id)
    if dd_trace is not None and record.trace_id is not None:
        entry["dd.trace_id"] = dd_trace
        dd_span = span_id_to_datadog(record.span_id)
        if dd_span is not None:
            entry["dd.span_id"] = dd_span
        entry["otel.trace_id"] = record.trace_id.strip().lower().rjust(32, "0")
        if dd_span is not None and record.span_id is not None:
            entry["otel.span_id"] = record.span_id.strip().lower().rjust(16, "0")
    for key, value in record.attributes.items():
        entry[f"{RESERVED_PREFIX}{key}" if key in RESERVED_KEYS else key] = value
    return entry


def _dumps(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, default=str)


@dataclasses.dataclass(frozen=True)
class SerializedEntry:
    """One serialized intake entry."""

    #: Compact JSON text.
    json: str
    #: UTF-8 size of :attr:`json`.
    size: int
    #: Whether the message was cut to fit (``grow.truncated: true`` is set).
    truncated: bool


def serialize_entry(
    entry: Mapping[str, Any], max_bytes: int = MAX_LOG_BYTES
) -> SerializedEntry | None:
    """Serialize an entry to at most ``max_bytes`` UTF-8 bytes (DECISIONS 7).

    An oversized entry gets its ``message`` cut at a character boundary and
    ``grow.truncated: true``. Returns ``None`` when even an empty message does not fit (the
    metadata alone is too large).
    """
    text = _dumps(entry)
    size = len(text.encode("utf-8"))
    if size <= max_bytes:
        return SerializedEntry(text, size, truncated=False)
    message = str(entry.get("message", ""))
    base = {**entry, "message": "", TRUNCATED_ATTRIBUTE: True}
    overhead = len(_dumps(base).encode("utf-8"))
    if overhead > max_bytes:
        return None
    budget = max_bytes - overhead

    def cost(end: int) -> int:
        return len(_dumps(message[:end]).encode("utf-8")) - 2

    lo, hi = 0, len(message)
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if cost(mid) <= budget:
            lo = mid
        else:
            hi = mid - 1
    cut = _dumps({**base, "message": message[:lo]})
    return SerializedEntry(cut, len(cut.encode("utf-8")), truncated=True)


@dataclasses.dataclass(frozen=True)
class DatadogLogEvent:
    """One log event returned by the Datadog search API, flattened."""

    #: Datadog log id.
    id: str
    #: Message text.
    message: str
    #: Event time (``None`` when missing or unparsable).
    timestamp: datetime | None = None
    #: Reserved ``host``.
    host: str | None = None
    #: Reserved ``service``.
    service: str | None = None
    #: Reserved ``status``.
    status: str | None = None
    #: Tags (``env:test`` …).
    tags: tuple[str, ...] = ()
    #: Custom attributes (``data[].attributes.attributes``) as returned (nested objects).
    attributes: Mapping[str, Any] = dataclasses.field(default_factory=dict)

    def record(self) -> LogRecord:
        """This event normalized to a :class:`LogRecord` (see :func:`event_to_log_record`)."""
        return event_to_log_record(self)


def _str(value: Any) -> str | None:
    return value if isinstance(value, str) else None


def parse_log_event(item: Any) -> DatadogLogEvent | None:
    """Parse one ``data[]`` item of a search response; ``None`` when it is not an object."""
    if not isinstance(item, Mapping):
        return None
    attrs = item.get("attributes")
    attrs = attrs if isinstance(attrs, Mapping) else {}
    timestamp: datetime | None = None
    ts = _str(attrs.get("timestamp"))
    if ts is not None:
        try:
            timestamp = parse_timestamp(ts)
        except ValidationError:
            timestamp = None
    tags = attrs.get("tags")
    custom = attrs.get("attributes")
    return DatadogLogEvent(
        id=_str(item.get("id")) or "",
        message=_str(attrs.get("message")) or "",
        timestamp=timestamp,
        host=_str(attrs.get("host")),
        service=_str(attrs.get("service")),
        status=_str(attrs.get("status")),
        tags=tuple(t for t in tags if isinstance(t, str)) if isinstance(tags, list) else (),
        attributes=dict(custom) if isinstance(custom, Mapping) else {},
    )


def _dotted(attrs: Mapping[str, Any], path: str) -> Any:
    if path in attrs:
        return attrs[path]
    current: Any = attrs
    for part in path.split("."):
        if not isinstance(current, Mapping):
            return None
        current = current.get(part)
    return current


def _tag_value(tags: Sequence[str], key: str) -> str | None:
    prefix = f"{key}:"
    for tag in tags:
        if tag.startswith(prefix):
            return tag[len(prefix) :]
    return None


def event_to_log_record(event: DatadogLogEvent) -> LogRecord:
    """Normalize a search event into a :class:`LogRecord` (API.md 6.1).

    ``status`` -> severity; ``service``/``host``/``env:``/``version:`` tags -> resource; custom
    attributes plus ``datadog.log_id`` and ``datadog.tags``; trace ids from ``otel.trace_id`` /
    ``otel.span_id``, else from ``dd.trace_id`` / ``dd.span_id`` (decimal -> hex; only the lower
    64 bits survive that way).
    """
    status = event.status or "info"
    resource: dict[str, AttrValue] = {}
    if event.service is not None:
        resource["service.name"] = event.service
    if event.host is not None:
        resource["host.name"] = event.host
    env = _tag_value(event.tags, "env")
    if env is not None:
        resource["deployment.environment"] = env
    version = _tag_value(event.tags, "version")
    if version is not None:
        resource["service.version"] = version
    converted = to_attr_value(event.attributes)
    attributes: dict[str, AttrValue] = dict(converted) if isinstance(converted, Mapping) else {}
    if event.id:
        attributes["datadog.log_id"] = event.id
    if event.tags:
        attributes["datadog.tags"] = list(event.tags)
    trace_id = datadog_id_to_hex(_dotted(event.attributes, "otel.trace_id"), 32) or (
        datadog_id_to_hex(_dotted(event.attributes, "dd.trace_id"), 32)
    )
    span_id: str | None = None
    if trace_id is not None:
        span_id = datadog_id_to_hex(_dotted(event.attributes, "otel.span_id"), 16) or (
            datadog_id_to_hex(_dotted(event.attributes, "dd.span_id"), 16)
        )
    return LogRecord(
        body=event.message,
        severity=severity_from_status(status),
        severity_text=status,
        timestamp=event.timestamp or _EPOCH,
        attributes=attributes,
        resource=resource,
        trace_id=trace_id,
        span_id=span_id,
    )
