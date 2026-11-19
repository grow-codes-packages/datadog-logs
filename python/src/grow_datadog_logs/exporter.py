"""The Datadog logs intake exporter (service code; API.md section 5, DECISIONS 4 and 7)."""

from __future__ import annotations

import dataclasses
import gzip as _gzip
from collections.abc import Sequence

from ._core import CancelledError, Request, ServiceError, UnavailableError, ValidationError
from ._datadog_http import HEADER_API_KEY, AsyncChannel, SyncChannel
from ._logging import ExportResult, LogRecord
from .config import DatadogLogsConfig
from .mapping import (
    MAX_ENTRIES_PER_REQUEST,
    MAX_LOG_BYTES,
    MAX_PAYLOAD_BYTES,
    EntryDefaults,
    serialize_entry,
    to_datadog_entry,
)

__all__ = [
    "INTAKE_PATH",
    "AsyncDatadogLogExporter",
    "DatadogLogExporter",
    "EncodedBatch",
    "IntakePayload",
]

#: Intake path (``POST``).
INTAKE_PATH = "/api/v2/logs"


@dataclasses.dataclass(frozen=True)
class IntakePayload:
    """One intake request body before compression."""

    #: The JSON array text.
    body: str
    #: Entries in the array.
    entries: int
    #: UTF-8 bytes of :attr:`body`.
    size: int


@dataclasses.dataclass(frozen=True)
class EncodedBatch:
    """Result of :meth:`DatadogLogExporter.encode`."""

    #: Request bodies in order.
    payloads: list[IntakePayload]
    #: Records whose metadata alone exceeds the per-log limit (never sent).
    oversized: int = 0
    #: Entries whose message was truncated (attribute ``grow.truncated: true``).
    truncated: int = 0


def _cap(value: int | None, maximum: int, name: str) -> int:
    if value is None:
        return maximum
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValidationError(f"logs.exporter: {name} must be a positive integer")
    return min(value, maximum)


class _ExporterBase:
    def __init__(
        self,
        config: DatadogLogsConfig,
        *,
        max_entries_per_request: int | None,
        max_payload_bytes: int | None,
        max_log_bytes: int | None,
    ) -> None:
        self.config = config
        self.max_entries_per_request = _cap(
            max_entries_per_request, MAX_ENTRIES_PER_REQUEST, "max_entries_per_request"
        )
        self.max_payload_bytes = _cap(max_payload_bytes, MAX_PAYLOAD_BYTES, "max_payload_bytes")
        self.max_log_bytes = _cap(max_log_bytes, MAX_LOG_BYTES, "max_log_bytes")
        self.defaults = EntryDefaults(
            service=config.service,
            hostname=config.hostname,
            env=config.env,
            version=config.version,
            tags=tuple(config.tags),
            source=config.source,
        )

    def encode(self, records: Sequence[LogRecord]) -> EncodedBatch:
        """Map, truncate and split records into intake request bodies (no I/O)."""
        payloads: list[IntakePayload] = []
        oversized = truncated = 0
        parts: list[str] = []
        size = 2

        def push() -> None:
            nonlocal parts, size
            if parts:
                payloads.append(IntakePayload("[" + ",".join(parts) + "]", len(parts), size))
            parts, size = [], 2

        for record in records:
            entry = serialize_entry(to_datadog_entry(record, self.defaults), self.max_log_bytes)
            if entry is None:
                oversized += 1
                continue
            if entry.truncated:
                truncated += 1
            extra = entry.size + (1 if parts else 0)
            if len(parts) >= self.max_entries_per_request or (
                parts and size + extra > self.max_payload_bytes
            ):
                push()
            size += entry.size + (1 if parts else 0)
            parts.append(entry.json)
        push()
        return EncodedBatch(payloads, oversized, truncated)

    def _request(self, body: str, gzip: bool, timeout_s: float | None) -> Request:
        api_key = self.config.api_key.reveal() if self.config.api_key else ""
        headers = {HEADER_API_KEY: api_key, "Content-Type": "application/json"}
        data = body.encode("utf-8")
        if gzip:
            headers["Content-Encoding"] = "gzip"
            data = _gzip.compress(data)
        timeout = self.config.request_timeout
        if timeout_s is not None and timeout_s > 0:
            timeout = min(timeout, timeout_s)
        return Request(
            "POST",
            INTAKE_PATH,
            operation="logs.send",
            content=data,
            headers=headers,
            # Datadog asks clients to retry the intake on 5xx (duplicates are possible).
            idempotent=True,
            timeout=timeout,
        )

    def _oversized_result(self, accepted: int, oversized: int, retries: int) -> ExportResult:
        return ExportResult(
            accepted=accepted,
            rejected=oversized,
            error=ValidationError(
                f"logs.send: {oversized} record(s) dropped: metadata exceeds "
                f"{self.max_log_bytes} bytes"
            ),
            retries=retries,
        )


def _wrap(err: Exception) -> Exception:
    if isinstance(err, ServiceError):
        return err
    return UnavailableError("logs.send: unexpected failure", cause=err)


class DatadogLogExporter(_ExporterBase):
    """:class:`LogExporter` for ``POST {intake}/api/v2/logs``.

    Maps records to Datadog JSON, truncates oversized messages (``grow.truncated: true``),
    splits batches at 1000 entries / 5 MB, gzips when asked and sends with header
    ``DD-API-KEY`` only. The intake POST is retried like an idempotent call (429, 5xx, network
    errors; duplicates are possible after an ambiguous failure). Limits above the Datadog
    limits are capped. Create it with :attr:`DatadogLogsClient.exporter`.
    """

    def __init__(
        self,
        channel: SyncChannel,
        config: DatadogLogsConfig,
        *,
        max_entries_per_request: int | None = None,
        max_payload_bytes: int | None = None,
        max_log_bytes: int | None = None,
    ) -> None:
        super().__init__(
            config,
            max_entries_per_request=max_entries_per_request,
            max_payload_bytes=max_payload_bytes,
            max_log_bytes=max_log_bytes,
        )
        self._channel = channel

    def send_payload(self, body: str, *, gzip: bool = True, timeout_s: float | None = None) -> int:
        """Post one JSON array body to the intake; returns the transport retries used.

        Raises the mapped SDK error.
        """
        resp = self._channel.request(self._request(body, gzip, timeout_s))
        return resp.attempts - 1

    def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """Send the records; never raises for service errors.

        A failing request stops the export: later records count as rejected and the error is
        returned in :attr:`ExportResult.error`.
        """
        batch = self.encode(records)
        accepted = retries = 0
        for payload in batch.payloads:
            try:
                retries += self.send_payload(payload.body, gzip=gzip, timeout_s=timeout_s)
            except Exception as exc:  # noqa: BLE001 - reported in the result
                return ExportResult(accepted, len(records) - accepted, _wrap(exc), retries)
            accepted += payload.entries
        if batch.oversized:
            return self._oversized_result(accepted, batch.oversized, retries)
        return ExportResult(accepted=accepted, retries=retries)

    def shutdown(self) -> None:
        """Nothing to release; the client owns the transport."""


class AsyncDatadogLogExporter(_ExporterBase):
    """:class:`AsyncLogExporter` twin of :class:`DatadogLogExporter`."""

    def __init__(
        self,
        channel: AsyncChannel,
        config: DatadogLogsConfig,
        *,
        max_entries_per_request: int | None = None,
        max_payload_bytes: int | None = None,
        max_log_bytes: int | None = None,
    ) -> None:
        super().__init__(
            config,
            max_entries_per_request=max_entries_per_request,
            max_payload_bytes=max_payload_bytes,
            max_log_bytes=max_log_bytes,
        )
        self._channel = channel

    async def send_payload(
        self, body: str, *, gzip: bool = True, timeout_s: float | None = None
    ) -> int:
        """Post one JSON array body to the intake; returns the transport retries used."""
        resp = await self._channel.request(self._request(body, gzip, timeout_s))
        return resp.attempts - 1

    async def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """Send the records; never raises for service errors (see the sync exporter)."""
        batch = self.encode(records)
        accepted = retries = 0
        for payload in batch.payloads:
            try:
                retries += await self.send_payload(payload.body, gzip=gzip, timeout_s=timeout_s)
            except CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - reported in the result
                return ExportResult(accepted, len(records) - accepted, _wrap(exc), retries)
            accepted += payload.entries
        if batch.oversized:
            return self._oversized_result(accepted, batch.oversized, retries)
        return ExportResult(accepted=accepted, retries=retries)

    async def shutdown(self) -> None:
        """Nothing to release; the client owns the transport."""
