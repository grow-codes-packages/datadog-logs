"""Factories the integration suite and the conformance runner use (owned by this repository).

Capabilities of the deployment under test (``datadog/fakeintake``):

* core: no ``version`` - Datadog is a SaaS and reports no server version
  (``server_version`` raises ``UnsupportedError``, asserted in test_datadog.py).
* logging: ``query`` - the logging target uses the real ``DatadogLogClient`` (real intake
  requests) and ``FakeintakeQuery``, which reads the captured payloads back, so roundtrip,
  batch and redaction prove what actually reached the intake. Datadog's own search API runs
  only against wire stubs here and in the live sandbox job. No ``tail``/``streams``/``labels``
  scenarios exist; tail and list_streams run against wire stubs, list_labels is unsupported.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from fakeintake import FakeIntake, FakeintakeQuery

import grow_datadog_logs as sdk
from grow_datadog_logs.testing import LoggingTarget
from grow_datadog_logs.testing.logging_conformance import batch_options_for

URL_VAR = "GROW_DATADOG_LOGS_URL"

#: Capabilities for the core scenarios ("version" = serverVersion; Datadog has none).
CORE_CAPABILITIES: set[str] = set()

#: Capabilities for the logging scenarios.
LOGGING_CAPABILITIES: set[str] = {"query"}

_RETRY = sdk.RetryPolicy(max_retries=2, initial_backoff=0.05)


def core_client(environ: Mapping[str, str], /, **overrides: Any) -> sdk.DatadogLogsClient:
    """A client configured from the test environment (/bootstrap/env)."""
    overrides.setdefault("retry", _RETRY)
    return sdk.DatadogLogsClient.from_env(environ, **overrides)


def async_core_client(environ: Mapping[str, str], /, **overrides: Any) -> sdk.AsyncDatadogLogsClient:
    overrides.setdefault("retry", _RETRY)
    return sdk.AsyncDatadogLogsClient.from_env(environ, **overrides)


def logging_target(env: Mapping[str, str], scenario: Mapping[str, Any]) -> LoggingTarget:
    """A ``LoggingTarget(client, query)`` for one scenario (client closes on teardown)."""
    client = core_client(env)
    options = batch_options_for(scenario) or sdk.BatchOptions(flush_interval_s=0.2)
    log_client = client.log_client(
        options,
        resource={"service.name": "grow-conformance-python", "deployment.environment": "conf"},
    )
    return LoggingTarget(
        client=_Closing(log_client, client), query=FakeintakeQuery(FakeIntake(env))
    )


class _Closing:
    """Log client wrapper whose ``close`` also closes the owning SDK client."""

    def __init__(self, log_client: sdk.DatadogLogClient, owner: sdk.DatadogLogsClient) -> None:
        self._log_client = log_client
        self._owner = owner

    def __getattr__(self, name: str) -> Any:
        return getattr(self._log_client, name)

    def close(self, timeout_s: float | None = None) -> None:
        self._log_client.close(timeout_s)
        self._owner.close()
