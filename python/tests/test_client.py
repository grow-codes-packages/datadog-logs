"""Datadog config, sites and the client surface (health, ping, validate_api_key, close)."""

from __future__ import annotations

import asyncio
import dataclasses
from typing import Any

import pytest

import grow_datadog_logs as sdk
from _support import (
    API_KEY,
    APP_KEY,
    MockServer,
    Reply,
    closed_port_url,
    dd_config,
    fixture_reply,
    spawn_capturing,
)

VALIDATE = "/api/v1/validate"
P = "GROW_DATADOG_LOGS_"


def client(url: str, **kw: Any) -> sdk.DatadogLogsClient:
    return sdk.DatadogLogsClient(dd_config(url, **kw))


def aclient(url: str, **kw: Any) -> sdk.AsyncDatadogLogsClient:
    return sdk.AsyncDatadogLogsClient(dd_config(url, **kw))


# --------------------------------------------------------------------------- sites


def test_sites_resolve_every_site() -> None:
    assert sdk.DEFAULT_SITE == "datadoghq.com"
    assert len(sdk.DATADOG_SITES) == 9
    for site in sdk.DATADOG_SITES:
        urls = sdk.resolve_site(site)
        assert urls.intake_url == f"https://http-intake.logs.{site}"
        assert urls.api_url == f"https://api.{site}"
    eu = sdk.resolve_site("  DatadogHQ.EU ")
    assert eu == sdk.DatadogSiteUrls(
        "datadoghq.eu", "https://http-intake.logs.datadoghq.eu", "https://api.datadoghq.eu"
    )
    assert sdk.resolve_site().site == "datadoghq.com"
    assert sdk.is_datadog_site("US5.datadoghq.com")
    assert not sdk.is_datadog_site("app.datadoghq.com")
    with pytest.raises(sdk.ConfigError, match="unknown Datadog site"):
        sdk.resolve_site("app.datadoghq.com")


# --------------------------------------------------------------------------- config


def test_parse_tags() -> None:
    assert sdk.parse_tags(None) == ()
    assert sdk.parse_tags("env:prod, team:sdk  team:sdk,,") == ("env:prod", "team:sdk")
    assert sdk.parse_tags(["a:1 b:2", "a:1", ""]) == ("a:1", "b:2")


def test_config_defaults_derive_urls_from_site() -> None:
    cfg = sdk.DatadogLogsConfig(api_key=API_KEY).validate()
    assert cfg.site == "datadoghq.com"
    assert cfg.intake_url == sdk.DEFAULT_INTAKE_URL == "https://http-intake.logs.datadoghq.com"
    assert cfg.api_url == sdk.DEFAULT_URL == cfg.url
    assert cfg.source == sdk.DEFAULT_SOURCE == "grow-sdk"
    assert cfg.tags == ()
    assert isinstance(cfg.api_key, sdk.Secret) and cfg.app_key is None
    eu = sdk.DatadogLogsConfig(api_key=API_KEY, site="datadoghq.eu")
    assert eu.intake_url == "https://http-intake.logs.datadoghq.eu"
    assert eu.api_url == eu.url == "https://api.datadoghq.eu"


def test_config_url_overrides() -> None:
    both = sdk.DatadogLogsConfig(api_key=API_KEY, url="http://fake:80/", site="nowhere.example")
    assert both.validate() is both
    assert both.intake_url == both.api_url == "http://fake:80/"
    one = sdk.DatadogLogsConfig(api_key=API_KEY, intake_url="http://intake", url="http://all")
    assert (one.intake_url, one.api_url, one.url) == ("http://intake", "http://all", "http://all")
    split = sdk.DatadogLogsConfig(
        api_key=API_KEY, site="?", intake_url="http://i:1", api_url="http://a:2"
    ).validate()
    assert split.url == "http://a:2"
    # replace keeps the resolved URLs
    assert dataclasses.replace(split, service="x").intake_url == "http://i:1"


def test_config_normalizes_fields() -> None:
    cfg = sdk.DatadogLogsConfig(
        api_key=API_KEY,
        app_key=APP_KEY,
        site=" US3.DatadogHQ.com ",
        service="  ",
        env=" prod ",
        version="1.2.3",
        hostname="",
        source=" ",
        tags="team:sdk, lang:py",
    )
    assert cfg.site == "us3.datadoghq.com"
    assert cfg.service is None and cfg.hostname is None
    assert (cfg.env, cfg.version, cfg.source) == ("prod", "1.2.3", "grow-sdk")
    assert cfg.tags == ("team:sdk", "lang:py")


@pytest.mark.parametrize(
    ("kwargs", "fragment"),
    [
        ({"site": "app.datadoghq.com"}, "unknown Datadog site"),
        ({"api_key": None}, "api_key is required"),
        ({"api_key": "   "}, "api_key must not be blank"),
        ({"app_key": "  "}, "app_key must not be blank"),
        ({"url": "ftp://x"}, "url"),
        ({"intake_url": "ftp://x"}, "intake_url"),
        ({"api_url": "nope"}, "api_url"),
        ({"connect_timeout": 0}, "connect_timeout"),
    ],
)
def test_config_validate(kwargs: dict[str, Any], fragment: str) -> None:
    values: dict[str, Any] = {"api_key": API_KEY}
    values.update(kwargs)
    with pytest.raises(sdk.ConfigError, match=fragment):
        sdk.DatadogLogsConfig(**values).validate()


def test_config_repr_redacts_keys_and_url_userinfo() -> None:
    cfg = sdk.DatadogLogsConfig(
        api_key=API_KEY, app_key=APP_KEY, intake_url="https://u:pw@intake", api_url="https://a"
    )
    for text in (repr(cfg), str(cfg)):
        assert API_KEY not in text and APP_KEY not in text and ":pw@" not in text
        assert "[REDACTED]@intake" in text
    assert cfg.known_secrets() == [API_KEY, APP_KEY]


def test_config_from_env_precedence() -> None:
    env = {
        "DD_API_KEY": "dd-api-key-1",
        "DD_APPLICATION_KEY": "dd-app-key-2",
        "DD_SITE": "datadoghq.eu",
        "DD_SERVICE": "svc",
        "DD_ENV": "dev",
        "DD_VERSION": "9",
        "DD_TAGS": "a:1 b:2",
        "DD_HOSTNAME": "h1",
    }
    cfg = sdk.DatadogLogsConfig.from_env(env)
    assert cfg.api_key == sdk.Secret("dd-api-key-1")
    assert cfg.app_key == sdk.Secret("dd-app-key-2")
    assert (cfg.site, cfg.service, cfg.env, cfg.version, cfg.hostname) == (
        "datadoghq.eu",
        "svc",
        "dev",
        "9",
        "h1",
    )
    assert cfg.tags == ("a:1", "b:2")
    env.update(
        {
            "DD_APP_KEY": "dd-app-key-3",
            P + "API_KEY": "grow-api-key",
            P + "SITE": "us5.datadoghq.com",
            P + "SERVICE": "grow-svc",
            P + "ENV": "grow-env",
            P + "VERSION": "10",
            P + "TAGS": "c:3",
            P + "HOSTNAME": "h2",
            P + "SOURCE": "custom",
            P + "INTAKE_URL": "http://intake:1",
            P + "API_URL": "http://api:2",
            P + "MAX_RETRIES": "1",
        }
    )
    cfg = sdk.DatadogLogsConfig.from_env(env)
    assert cfg.api_key == sdk.Secret("grow-api-key")
    assert cfg.app_key == sdk.Secret("dd-app-key-3")
    assert (cfg.site, cfg.service, cfg.env, cfg.version, cfg.hostname, cfg.source) == (
        "us5.datadoghq.com",
        "grow-svc",
        "grow-env",
        "10",
        "h2",
        "custom",
    )
    assert (cfg.intake_url, cfg.api_url, cfg.tags) == ("http://intake:1", "http://api:2", ("c:3",))
    assert cfg.retry.max_retries == 1
    explicit = sdk.DatadogLogsConfig.from_env(env, env="explicit", service=None)
    assert (explicit.env, explicit.service) == ("explicit", "grow-svc")
    with pytest.raises(sdk.ConfigError, match="api_key is required"):
        sdk.DatadogLogsConfig.from_env({})


def test_from_env_reads_os_environ(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DD_API_KEY", "os-api-key-1")
    monkeypatch.setenv(P + "URL", "http://from-os:9")
    with sdk.DatadogLogsClient.from_env() as c:
        assert c.config.intake_url == "http://from-os:9"
    assert sdk.DatadogLogsClient().config.api_url == "http://from-os:9"
    assert sdk.AsyncDatadogLogsClient().config.url == "http://from-os:9"
    assert sdk.AsyncDatadogLogsClient.from_env(site="x", url="http://y").config.url == "http://y"


# --------------------------------------------------------------------------- sync client


def test_health_ping_validate(server: MockServer) -> None:
    """core.health.ok"""
    server.defaults[VALIDATE] = fixture_reply("validateApiKey.ok")
    with client(server.url) as c:
        status = c.health()
        assert status == sdk.HealthStatus(True, "ok", {"apiKeyValid": "true"})
        assert c.ping() >= 0
        assert c.validate_api_key() is True
        with pytest.raises(sdk.UnsupportedError):
            c.server_version()
    seen = server.seen[0]
    assert seen.method == "GET" and seen.headers["DD-API-KEY"] == API_KEY
    assert "DD-APPLICATION-KEY" not in seen.headers
    assert seen.headers["User-Agent"].startswith("grow-codes-datadog-logs/")


def test_validate_api_key_false_on_403(server: MockServer) -> None:
    server.defaults[VALIDATE] = fixture_reply("validateApiKey.403_invalid_key")
    with client(server.url) as c:
        assert c.validate_api_key() is False
        with pytest.raises(sdk.AuthenticationError) as info:
            c.ping()
        assert info.value.http_status == 403 and not info.value.retryable
        assert isinstance(info.value.cause, sdk.PermissionDeniedError)
        assert c.health() == sdk.HealthStatus(
            False, "unauthorized", {"httpStatus": "403", "apiKeyValid": "false"}
        )


def test_validate_and_ping_reject_unexpected_bodies(server: MockServer) -> None:
    server.defaults[VALIDATE] = fixture_reply("health.fakeintake.ok")  # 200, empty body
    with client(server.url) as c:
        with pytest.raises(sdk.UnavailableError, match="unexpected response") as info:
            c.ping()
        assert not info.value.retryable
        with pytest.raises(sdk.UnavailableError):
            c.validate_api_key()
        assert c.health() == sdk.HealthStatus(True, "ok", {"apiKeyValid": "false"})
        server.defaults[VALIDATE] = Reply(200, {"valid": False})
        assert c.validate_api_key() is False
        server.defaults[VALIDATE] = Reply(400, {"errors": ["bad"]})
        with pytest.raises(sdk.ValidationError):
            c.validate_api_key()


def test_health_variants(server: MockServer) -> None:
    """core.health.unhealthy"""
    with client(server.url) as c:
        server.enqueue(VALIDATE, Reply(401, {"errors": ["Unauthorized"]}))
        assert c.health().status == "unauthorized"
        server.enqueue(VALIDATE, Reply(429, {"errors": ["slow down"]}))
        assert c.health() == sdk.HealthStatus(
            False, "degraded", {"httpStatus": "429", "error": "RATE_LIMITED"}
        )
        server.enqueue(VALIDATE, Reply(503, {"errors": ["down"]}))
        status = c.health()
        assert (status.healthy, status.status, status.details["httpStatus"]) == (
            False,
            "unhealthy",
            "503",
        )
        server.enqueue(VALIDATE, Reply(404, "nope"))
        assert c.health().status == "unhealthy"


def test_core_health_unreachable() -> None:
    """core.health.unreachable"""
    with client(closed_port_url()) as c:
        status = c.health()
    assert (status.healthy, status.status) == (False, "unreachable")


def test_health_raises_cancelled(server: MockServer) -> None:
    server.defaults[VALIDATE] = Reply(200, {"valid": True})
    c = client(server.url)
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.health()


def test_core_close_idempotent_flushes_log_clients(server: MockServer) -> None:
    """core.close.idempotent"""
    server.defaults["/api/v2/logs"] = Reply(202, {})
    c = client(server.url, service="svc", env="test", version="1", hostname="h")
    lc = c.log_client(sdk.BatchOptions(flush_interval_s=3600))
    assert lc.resource == {
        "service.name": "svc",
        "service.version": "1",
        "deployment.environment": "test",
        "host.name": "h",
    }
    assert c.log_client(resource={"service.name": "other"}).resource["service.name"] == "other"
    lc.send(sdk.LogInput("bye"))
    assert c.exporter is c.exporter and lc.exporter is c.exporter
    c.close()
    c.close()
    assert lc.stats().sent == 1
    assert [s.path for s in server.seen] == ["/api/v2/logs"]
    with pytest.raises(sdk.CancelledError):
        c.log_client()
    assert "DatadogLogsClient(config=DatadogLogsConfig(" in repr(c)
    assert API_KEY not in repr(c)


def test_query_requires_app_key(server: MockServer) -> None:
    with client(server.url, app_key=None) as c, pytest.raises(sdk.ConfigError, match="app_key"):
        c.query()
    with client(server.url) as c:
        assert c.query() is c.query()


def test_rate_limit_reset_is_used_as_retry_after(server: MockServer) -> None:
    server.enqueue(
        VALIDATE,
        Reply(429, {"errors": ["Too many requests"]}, {"X-RateLimit-Reset": "0.05"}),
        Reply(200, {"valid": True}),
    )
    with client(server.url) as c:
        assert c.validate_api_key() is True
    server.enqueue(VALIDATE, Reply(429, {}, {"X-RateLimit-Reset": "3600"}))
    with client(server.url) as c, pytest.raises(sdk.RateLimitError) as info:
        c.validate_api_key()
    assert info.value.retry_after == 3600.0


def test_rate_limit_hint_rules() -> None:
    import httpx

    def resp(status: int, **headers: str) -> httpx.Response:
        return httpx.Response(status, headers=headers)

    from grow_datadog_logs._datadog_http import apply_rate_limit_hint

    for r, want in (
        (resp(429, **{"X-RateLimit-Reset": " 7 "}), "7"),
        (resp(503, **{"X-RateLimit-Reset": "1.5"}), "1.5"),
        (resp(429, **{"X-RateLimit-Reset": "soon"}), None),
        (resp(429, **{"Retry-After": "2", "X-RateLimit-Reset": "9"}), "2"),
        (resp(500, **{"X-RateLimit-Reset": "9"}), None),
        (resp(429), None),
    ):
        apply_rate_limit_hint(r)
        assert r.headers.get("retry-after") == want


def test_map_datadog_error() -> None:
    other = sdk.PermissionDeniedError("x", http_status=None)
    assert sdk.map_datadog_error(other) is other
    nf = sdk.NotFoundError("y", http_status=404)
    assert sdk.map_datadog_error(nf) is nf
    mapped = sdk.map_datadog_error(sdk.PermissionDeniedError("z", http_status=403, request_id="r"))
    assert isinstance(mapped, sdk.AuthenticationError)
    assert (mapped.http_status, mapped.request_id, mapped.message) == (403, "r", "z")


def test_secrets_never_leak_into_errors(server: MockServer) -> None:
    server.defaults[VALIDATE] = Reply(400, {"errors": [f"bad key {API_KEY}"]})
    with client(server.url) as c, pytest.raises(sdk.ValidationError) as info:
        c.ping()
    assert API_KEY not in str(info.value) and "[REDACTED]" in str(info.value)


# --------------------------------------------------------------------------- async client


async def test_async_client(server: MockServer) -> None:
    server.defaults[VALIDATE] = Reply(200, {"valid": True})
    server.defaults["/api/v2/logs"] = Reply(202, {})
    async with aclient(server.url, service="svc") as c:
        assert (await c.health()).healthy
        assert await c.ping() >= 0
        assert await c.validate_api_key() is True
        with pytest.raises(sdk.UnsupportedError):
            await c.server_version()
        lc = c.log_client(sdk.BatchOptions(flush_interval_s=3600))
        assert lc.resource == {"service.name": "svc"}
        assert c.exporter is c.exporter and lc.exporter is c.exporter
        lc.send(sdk.LogInput("async bye"))
        assert c.query() is c.query()
        assert c.resources is not None
        assert "AsyncDatadogLogsClient(" in repr(c)
    assert lc.stats().sent == 1
    await c.aclose()
    with pytest.raises(sdk.CancelledError):
        c.log_client()


async def test_async_validate_403_and_health_variants(server: MockServer) -> None:
    server.defaults[VALIDATE] = fixture_reply("validateApiKey.403_missing_key")
    c = aclient(server.url, app_key=None)
    assert await c.validate_api_key() is False
    assert (await c.health()).status == "unauthorized"
    with pytest.raises(sdk.AuthenticationError):
        await c.ping()
    server.defaults[VALIDATE] = Reply(500, {"errors": ["x"]})
    with pytest.raises(sdk.UnavailableError):
        await c.validate_api_key()
    with pytest.raises(sdk.ConfigError):
        c.query()
    await c.aclose()
    await c.close()


async def test_async_health_unreachable_and_cancel(server: MockServer) -> None:
    """core.health.unreachable (async)"""
    c = aclient(closed_port_url())
    assert (await c.health()).status == "unreachable"
    await c.aclose()
    server.enqueue(VALIDATE, Reply(200, {"valid": True}, delay=2.0))
    c = aclient(server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(c.health(), errors)
    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)
    await c.aclose()
