"""Datadog-specific HTTP plumbing on top of the generated transport (service code).

* DECISIONS 3: a 403 from the intake, ``/api/v1/validate`` or the query API means a missing or
  invalid key (or, on the query API, a key without the needed permission, which Datadog does not
  tell apart) and maps to :class:`AuthenticationError`, keeping ``http_status`` 403.
* DECISIONS 5: when a 429/503 response has no ``Retry-After`` but carries ``X-RateLimit-Reset``
  (seconds until the window resets), that value is exposed as ``Retry-After`` so the transport
  honours it as the retry hint.
"""

from __future__ import annotations

import dataclasses
import re

import httpx

from ._core import (
    AsyncHttpTransport,
    AuthenticationError,
    CancelToken,
    HttpTransport,
    PermissionDeniedError,
    Request,
    Response,
    ServiceError,
)
from .config import DatadogLogsConfig

__all__ = [
    "HEADER_API_KEY",
    "HEADER_APP_KEY",
    "RATE_LIMIT_RESET_HEADER",
    "apply_rate_limit_hint",
    "map_datadog_error",
]

#: Header carrying the API key.
HEADER_API_KEY = "DD-API-KEY"
#: Header carrying the application key (query API only).
HEADER_APP_KEY = "DD-APPLICATION-KEY"
#: Rate-limit reset header sent by the Datadog API.
RATE_LIMIT_RESET_HEADER = "x-ratelimit-reset"

_NUMBER = re.compile(r"^\d+(\.\d+)?$")


def apply_rate_limit_hint(response: httpx.Response) -> None:
    """Copy ``X-RateLimit-Reset`` into ``Retry-After`` on a 429/503 response without one."""
    if response.status_code not in (429, 503) or "retry-after" in response.headers:
        return
    reset = response.headers.get(RATE_LIMIT_RESET_HEADER, "").strip()
    if _NUMBER.match(reset):
        response.headers["Retry-After"] = reset


async def _apply_rate_limit_hint_async(response: httpx.Response) -> None:
    apply_rate_limit_hint(response)


def map_datadog_error(err: ServiceError) -> ServiceError:
    """Map Datadog's 403 (:class:`PermissionDeniedError`) onto :class:`AuthenticationError`."""
    if not isinstance(err, PermissionDeniedError) or err.http_status != 403:
        return err
    return AuthenticationError(
        err.message, http_status=403, request_id=err.request_id, retryable=False, cause=err
    )


def _with_url(config: DatadogLogsConfig, url: str) -> DatadogLogsConfig:
    return dataclasses.replace(config, url=url)


class SyncChannel:
    """A transport bound to one Datadog base URL (intake or API) with the Datadog mapping."""

    def __init__(
        self,
        config: DatadogLogsConfig,
        base_url: str,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.transport = HttpTransport(
            _with_url(config, base_url),
            known_secrets=config.known_secrets,
            http_transport=http_transport,
        )
        # The generated transport owns the httpx client; hook the response to add the hint.
        self.transport._client.event_hooks["response"].append(apply_rate_limit_hint)

    def request(self, req: Request, cancel: CancelToken | None = None) -> Response:
        """Send ``req``; errors are mapped with :func:`map_datadog_error`."""
        try:
            return self.transport.request(req, cancel=cancel)
        except ServiceError as err:
            mapped = map_datadog_error(err)
            if mapped is err:
                raise
            raise mapped from err

    def close(self) -> None:
        self.transport.close()


class AsyncChannel:
    """Async :class:`SyncChannel`."""

    def __init__(
        self,
        config: DatadogLogsConfig,
        base_url: str,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.transport = AsyncHttpTransport(
            _with_url(config, base_url),
            known_secrets=config.known_secrets,
            http_transport=http_transport,
        )
        self.transport._client.event_hooks["response"].append(_apply_rate_limit_hint_async)

    async def request(self, req: Request) -> Response:
        """Send ``req``; errors are mapped with :func:`map_datadog_error`."""
        try:
            return await self.transport.request(req)
        except ServiceError as err:
            mapped = map_datadog_error(err)
            if mapped is err:
                raise
            raise mapped from err

    async def aclose(self) -> None:
        await self.transport.aclose()
