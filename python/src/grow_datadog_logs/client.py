"""Datadog Log Management clients, sync and async (service code; API.md section 4, DECISIONS 2, 3).

>>> client = DatadogLogsClient.from_env()  # doctest: +SKIP
>>> logs = client.log_client(resource={"service.name": "checkout"})  # doctest: +SKIP
>>> logs.logger().info("user signed in", {"user": {"id": 42}})  # doctest: +SKIP
>>> client.close()  # doctest: +SKIP
"""

from __future__ import annotations

import contextlib
import threading
import time
from collections.abc import Mapping
from types import TracebackType
from typing import Any

import httpx

from ._core import (
    AuthenticationError,
    CancelledError,
    CancelToken,
    ConfigError,
    HealthStatus,
    RateLimitError,
    Request,
    ResourceRegistry,
    Response,
    ServiceError,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
)
from ._datadog_http import HEADER_API_KEY, AsyncChannel, SyncChannel
from ._logging import (
    AsyncBatchingLogClient,
    AttrValue,
    BatchingLogClient,
    BatchOptions,
    RedactionProcessor,
)
from .config import DatadogLogsConfig
from .exporter import AsyncDatadogLogExporter, DatadogLogExporter
from .query import AsyncDatadogLogsQuery, DatadogLogsQuery

__all__ = [
    "VALIDATE_PATH",
    "AsyncDatadogLogClient",
    "AsyncDatadogLogsClient",
    "DatadogLogClient",
    "DatadogLogsClient",
]

#: API key validation path.
VALIDATE_PATH = "/api/v1/validate"

_DEFAULT_REDACTION = RedactionProcessor()


class DatadogLogClient(BatchingLogClient):
    """:class:`LogClient` that ships records to the Datadog logs intake.

    Records go through a :class:`DatadogLogExporter` and a background :class:`BatchProcessor`.
    Create it with :meth:`DatadogLogsClient.log_client`.
    """

    def __init__(
        self,
        exporter: DatadogLogExporter,
        *,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> None:
        super().__init__(
            exporter, options=options, resource=resource, redaction=redaction, correlate=correlate
        )
        #: The exporter used by this client.
        self.exporter = exporter


class AsyncDatadogLogClient(AsyncBatchingLogClient):
    """Async :class:`DatadogLogClient`; create it with :meth:`AsyncDatadogLogsClient.log_client`."""

    def __init__(
        self,
        exporter: AsyncDatadogLogExporter,
        *,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> None:
        super().__init__(
            exporter, options=options, resource=resource, redaction=redaction, correlate=correlate
        )
        #: The exporter used by this client.
        self.exporter = exporter


# --------------------------------------------------------------------------- shared helpers


def _validate_request(
    config: DatadogLogsConfig, operation: str, max_retries: int | None
) -> Request:
    api_key = config.api_key.reveal() if config.api_key else ""
    return Request(
        "GET",
        VALIDATE_PATH,
        operation=operation,
        headers={HEADER_API_KEY: api_key},
        max_retries=max_retries,
    )


def _health_ok(resp: Response) -> HealthStatus:
    try:
        body: Any = resp.json()
    except ServiceError:
        body = None
    valid = isinstance(body, Mapping) and body.get("valid") is True
    return HealthStatus(True, "ok", {"apiKeyValid": "true" if valid else "false"})


def _health_error(err: ServiceError) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    if isinstance(err, AuthenticationError) and err.http_status in (401, 403):
        return HealthStatus(
            False, "unauthorized", {"httpStatus": str(err.http_status), "apiKeyValid": "false"}
        )
    if isinstance(err, RateLimitError):
        return HealthStatus(False, "degraded", {"httpStatus": "429", "error": err.code.value})
    if err.http_status is None and isinstance(err, (UnavailableError, TimeoutError)):
        return HealthStatus(False, "unreachable", {"error": err.message})
    details = {"error": err.message}
    if err.http_status is not None:
        details["httpStatus"] = str(err.http_status)
    return HealthStatus(False, "unhealthy", details)


def _ping_check(resp: Response) -> None:
    try:
        body: Any = resp.json()
    except ServiceError:
        body = None
    if not (isinstance(body, Mapping) and body.get("valid") is True):
        raise UnavailableError(
            "ping: unexpected response from /api/v1/validate",
            retryable=False,
            http_status=resp.status,
        )


def _validate_result(resp: Response) -> bool:
    try:
        body: Any = resp.json()
    except ServiceError:
        body = None
    valid = body.get("valid") if isinstance(body, Mapping) else None
    if not isinstance(valid, bool):
        raise UnavailableError(
            "validate_api_key: unexpected response from /api/v1/validate",
            retryable=False,
            http_status=resp.status,
        )
    return valid


def _is_forbidden(err: ServiceError) -> bool:
    return isinstance(err, AuthenticationError) and err.http_status == 403


def _resource(
    config: DatadogLogsConfig, extra: Mapping[str, AttrValue] | None
) -> dict[str, AttrValue]:
    out: dict[str, AttrValue] = {}
    for key, value in (
        ("service.name", config.service),
        ("service.version", config.version),
        ("deployment.environment", config.env),
        ("host.name", config.hostname),
    ):
        if value is not None:
            out[key] = value
    out.update(extra or {})
    return out


def _unsupported_version() -> UnsupportedError:
    return UnsupportedError("server_version: Datadog does not report a server version")


# --------------------------------------------------------------------------- sync


class DatadogLogsClient:
    """Synchronous client for Datadog Log Management.

    API key checks (:meth:`health`, :meth:`ping`, :meth:`validate_api_key`), the logs intake
    (:meth:`log_client`, :attr:`exporter`) and the query API (:meth:`query`, needs ``app_key``).
    Thread-safe; use as a context manager or call :meth:`close`. I/O methods accept ``cancel=``
    (a :class:`CancelToken`) to abort from another thread.
    """

    def __init__(
        self,
        config: DatadogLogsConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        if config is None:
            config = DatadogLogsConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._lock = threading.Lock()
        self._closed = False
        self._log_clients: list[DatadogLogClient] = []
        self._intake = SyncChannel(self.config, self.config.intake_url, http_transport)
        self._api = SyncChannel(self.config, self.config.api_url, http_transport)
        self._exporter: DatadogLogExporter | None = None
        self._query: DatadogLogsQuery | None = None

    @classmethod
    def from_env(
        cls, environ: Mapping[str, str] | None = None, /, **overrides: Any
    ) -> DatadogLogsClient:
        """Build a client from ``GROW_DATADOG_LOGS_*``, ``DD_API_KEY``, ``DD_SITE`` … variables."""
        return cls(DatadogLogsConfig.from_env(environ, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources (tails) stopped by :meth:`close`."""
        return self._resources

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Reachability through ``GET /api/v1/validate`` (Datadog has no health endpoint).

        2xx -> healthy (``details["apiKeyValid"]``); 401/403 -> ``healthy=False,
        status="unauthorized"``; 429 -> ``degraded``; 5xx -> ``unhealthy``; network errors ->
        ``unreachable``. Never raises except :class:`CancelledError` / :class:`ConfigError`.
        """
        try:
            resp = self._api.request(_validate_request(self.config, "health", 0), cancel)
        except ServiceError as err:
            return _health_error(err)
        return _health_ok(resp)

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Authenticated round trip ``GET /api/v1/validate``; returns the latency in ms.

        403 -> :class:`AuthenticationError`; a 2xx without ``{"valid": true}`` ->
        :class:`UnavailableError`.
        """
        started = time.monotonic()
        _ping_check(self._api.request(_validate_request(self.config, "ping", None), cancel))
        return (time.monotonic() - started) * 1000.0

    def validate_api_key(self, *, cancel: CancelToken | None = None) -> bool:
        """Whether the API key is valid: ``True`` for ``{"valid": true}``, ``False`` for 403.

        Other failures raise the mapped error.
        """
        try:
            resp = self._api.request(
                _validate_request(self.config, "validate_api_key", None), cancel
            )
        except ServiceError as err:
            if _is_forbidden(err):
                return False
            raise
        return _validate_result(resp)

    def server_version(self, *, cancel: CancelToken | None = None) -> str:
        """Datadog is a SaaS without a version endpoint: always raises :class:`UnsupportedError`."""
        raise _unsupported_version()

    @property
    def exporter(self) -> DatadogLogExporter:
        """The intake exporter (one per client, Datadog limits)."""
        with self._lock:
            if self._exporter is None:
                self._exporter = DatadogLogExporter(self._intake, self.config)
            return self._exporter

    def log_client(
        self,
        options: BatchOptions | None = None,
        *,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
        exporter: DatadogLogExporter | None = None,
    ) -> DatadogLogClient:
        """A batching :class:`DatadogLogClient` over :attr:`exporter` (or ``exporter``).

        The resource defaults to the configured ``service``, ``version``, ``env`` and
        ``hostname`` (``resource`` keys win). :meth:`close` flushes and closes every log client
        created here.
        """
        exp = exporter if exporter is not None else self.exporter
        client = DatadogLogClient(
            exp,
            options=options,
            resource=_resource(self.config, resource),
            redaction=redaction,
            correlate=correlate,
        )
        with self._lock:
            if self._closed:
                client.close()
                raise CancelledError("log_client: client is closed")
            self._log_clients.append(client)
        return client

    def new_exporter(
        self,
        *,
        max_entries_per_request: int | None = None,
        max_payload_bytes: int | None = None,
        max_log_bytes: int | None = None,
    ) -> DatadogLogExporter:
        """An intake exporter with smaller request limits (values above Datadog's are capped)."""
        return DatadogLogExporter(
            self._intake,
            self.config,
            max_entries_per_request=max_entries_per_request,
            max_payload_bytes=max_payload_bytes,
            max_log_bytes=max_log_bytes,
        )

    def query(self) -> DatadogLogsQuery:
        """The query client (search, aggregate, indexes); :class:`ConfigError` without app key."""
        with self._lock:
            if self._query is None:
                self._query = DatadogLogsQuery(self._api, self.config, self._resources)
            return self._query

    def close(self) -> None:
        """Flush and close every log client, stop tails and release connections (idempotent)."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            clients, self._log_clients = self._log_clients, []
        for client in clients:
            with contextlib.suppress(Exception):  # closing must not fail half way
                client.close()
        self._resources.stop_all()
        self._intake.close()
        self._api.close()

    def __enter__(self) -> DatadogLogsClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"DatadogLogsClient(config={self.config!r})"


# --------------------------------------------------------------------------- async


class AsyncDatadogLogsClient:
    """Asynchronous :class:`DatadogLogsClient`; use ``async with`` or call :meth:`aclose`.

    Cancel a call by cancelling its task: the SDK raises :class:`CancelledError`, which is also
    an :class:`asyncio.CancelledError`.
    """

    def __init__(
        self,
        config: DatadogLogsConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if config is None:
            config = DatadogLogsConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._closed = False
        self._log_clients: list[AsyncDatadogLogClient] = []
        self._intake = AsyncChannel(self.config, self.config.intake_url, http_transport)
        self._api = AsyncChannel(self.config, self.config.api_url, http_transport)
        self._exporter: AsyncDatadogLogExporter | None = None
        self._query: AsyncDatadogLogsQuery | None = None

    @classmethod
    def from_env(
        cls, environ: Mapping[str, str] | None = None, /, **overrides: Any
    ) -> AsyncDatadogLogsClient:
        """Build a client from the environment (see :meth:`DatadogLogsConfig.from_env`)."""
        return cls(DatadogLogsConfig.from_env(environ, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`aclose`."""
        return self._resources

    async def health(self) -> HealthStatus:
        """Reachability through ``GET /api/v1/validate`` (see :meth:`DatadogLogsClient.health`)."""
        try:
            resp = await self._api.request(_validate_request(self.config, "health", 0))
        except ServiceError as err:
            return _health_error(err)
        return _health_ok(resp)

    async def ping(self) -> float:
        """Authenticated round trip ``GET /api/v1/validate``; returns the latency in ms."""
        started = time.monotonic()
        _ping_check(await self._api.request(_validate_request(self.config, "ping", None)))
        return (time.monotonic() - started) * 1000.0

    async def validate_api_key(self) -> bool:
        """``True`` for ``{"valid": true}``, ``False`` for 403; other failures raise."""
        try:
            resp = await self._api.request(_validate_request(self.config, "validate_api_key", None))
        except ServiceError as err:
            if _is_forbidden(err):
                return False
            raise
        return _validate_result(resp)

    async def server_version(self) -> str:
        """Always raises :class:`UnsupportedError` (Datadog reports no server version)."""
        raise _unsupported_version()

    @property
    def exporter(self) -> AsyncDatadogLogExporter:
        """The intake exporter (one per client, Datadog limits)."""
        if self._exporter is None:
            self._exporter = AsyncDatadogLogExporter(self._intake, self.config)
        return self._exporter

    def log_client(
        self,
        options: BatchOptions | None = None,
        *,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
        exporter: AsyncDatadogLogExporter | None = None,
    ) -> AsyncDatadogLogClient:
        """A batching :class:`AsyncDatadogLogClient` (see :meth:`DatadogLogsClient.log_client`)."""
        if self._closed:
            raise CancelledError("log_client: client is closed")
        client = AsyncDatadogLogClient(
            exporter if exporter is not None else self.exporter,
            options=options,
            resource=_resource(self.config, resource),
            redaction=redaction,
            correlate=correlate,
        )
        self._log_clients.append(client)
        return client

    def new_exporter(
        self,
        *,
        max_entries_per_request: int | None = None,
        max_payload_bytes: int | None = None,
        max_log_bytes: int | None = None,
    ) -> AsyncDatadogLogExporter:
        """An intake exporter with smaller request limits (values above Datadog's are capped)."""
        return AsyncDatadogLogExporter(
            self._intake,
            self.config,
            max_entries_per_request=max_entries_per_request,
            max_payload_bytes=max_payload_bytes,
            max_log_bytes=max_log_bytes,
        )

    def query(self) -> AsyncDatadogLogsQuery:
        """The query client. :class:`ConfigError` without ``app_key``."""
        if self._query is None:
            self._query = AsyncDatadogLogsQuery(self._api, self.config)
        return self._query

    async def aclose(self) -> None:
        """Flush and close every log client, stop tails and release connections (idempotent)."""
        if self._closed:
            return
        self._closed = True
        clients, self._log_clients = self._log_clients, []
        for client in clients:
            with contextlib.suppress(Exception):  # closing must not fail half way
                await client.close()
        if self._query is not None:
            await self._query.stop_tails()
        self._resources.stop_all()
        await self._intake.aclose()
        await self._api.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncDatadogLogsClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncDatadogLogsClient(config={self.config!r})"
