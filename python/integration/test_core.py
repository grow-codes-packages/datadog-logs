"""Core round trips against the real service plus the core and logging conformance scenarios."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest
from factories import (
    CORE_CAPABILITIES,
    LOGGING_CAPABILITIES,
    async_core_client,
    core_client,
    logging_target,
)
from fakeintake import FakeIntake

import grow_datadog_logs as sdk
from grow_datadog_logs.testing import ConformanceRunner, assert_scenario, pytest_params


def test_health(client: sdk.DatadogLogsClient) -> None:
    status = client.health()
    assert status == sdk.HealthStatus(True, "ok", {"apiKeyValid": "true"}), status


def test_ping(client: sdk.DatadogLogsClient) -> None:
    assert client.ping() > 0


def test_server_version_is_unsupported(client: sdk.DatadogLogsClient) -> None:
    assert "version" not in CORE_CAPABILITIES
    with pytest.raises(sdk.UnsupportedError):
        client.server_version()


def test_close_is_idempotent(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()


def test_async_client(env: dict[str, str]) -> None:
    async def run() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() > 0
            assert await c.validate_api_key() is True
            with pytest.raises(sdk.UnsupportedError):
                await c.server_version()

    asyncio.run(run())


def test_rejected_key(env: dict[str, str], intake: FakeIntake) -> None:
    """The container answers 403 like Datadog does for a missing or invalid key."""
    intake.override("GET", "/api/v1/validate", 403, '{"errors":["Forbidden"]}')
    with core_client(env, api_key="definitely-wrong-key") as c:
        with pytest.raises(sdk.AuthenticationError) as info:
            c.ping()
        assert info.value.http_status == 403
        assert "definitely-wrong-key" not in str(info.value) + repr(info.value)
        assert c.validate_api_key() is False
        assert c.health().status == "unauthorized"


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)


@pytest.mark.parametrize("scenario", pytest_params(categories=["logging"]))
def test_logging_conformance(
    env: dict[str, str], intake: FakeIntake, scenario: dict[str, Any]
) -> None:
    runner = ConformanceRunner(
        {"logging": lambda ctx: logging_target(env, ctx.scenario)},
        capabilities=LOGGING_CAPABILITIES,
    )
    result = assert_scenario(runner, scenario)
    assert result.status == "passed"
