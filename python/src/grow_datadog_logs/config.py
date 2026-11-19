"""Configuration for the Datadog Log Management client (service code, API.md section 2).

Environment (:meth:`DatadogLogsConfig.from_env`), precedence: explicit keyword >
``GROW_DATADOG_LOGS_<NAME>`` > Datadog variable > default.

=================  =============================  ==================================
Field              ``GROW_DATADOG_LOGS_*``        Datadog variable
=================  =============================  ==================================
``api_key``        ``API_KEY``                    ``DD_API_KEY``
``app_key``        ``APP_KEY``                    ``DD_APP_KEY``, ``DD_APPLICATION_KEY``
``site``           ``SITE``                       ``DD_SITE``
``url``            ``URL``                        —
``intake_url``     ``INTAKE_URL``                 —
``api_url``        ``API_URL``                    —
``service``        ``SERVICE``                    ``DD_SERVICE``
``env``            ``ENV``                        ``DD_ENV``
``version``        ``VERSION``                    ``DD_VERSION``
``tags``           ``TAGS``                       ``DD_TAGS``
``hostname``       ``HOSTNAME``                   ``DD_HOSTNAME``
``source``         ``SOURCE``                     —
=================  =============================  ==================================

plus the common ``CONNECT_TIMEOUT``, ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``, ``MAX_RETRIES``,
``CA_CERT``, ``CLIENT_CERT``, ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY`` and ``PROXY``.
"""

from __future__ import annotations

import dataclasses
import re
from collections.abc import Iterable, Mapping, Sequence
from typing import Any, ClassVar, TypeVar
from urllib.parse import urlsplit

from ._core import BaseConfig, ConfigError, EnvReader, Secret, redact_url
from ._core.redaction import safe_repr
from .sites import DEFAULT_SITE, is_datadog_site, normalize_site, resolve_site

__all__ = ["DEFAULT_INTAKE_URL", "DEFAULT_SOURCE", "DEFAULT_URL", "DatadogLogsConfig", "parse_tags"]

#: API base URL of the default site.
DEFAULT_URL = "https://api." + DEFAULT_SITE
#: Logs intake base URL of the default site.
DEFAULT_INTAKE_URL = "https://http-intake.logs." + DEFAULT_SITE
#: Default ``ddsource`` of every log (DECISIONS 9).
DEFAULT_SOURCE = "grow-sdk"

_TAG_SPLIT = re.compile(r"[\s,]+")

_C = TypeVar("_C", bound="DatadogLogsConfig")


def parse_tags(value: str | Iterable[str] | None) -> tuple[str, ...]:
    """Parse Datadog tags: ``key:value`` items separated by commas and/or whitespace.

    Accepts one string (the ``DD_TAGS`` convention) or a list of strings. Empty items are dropped
    and duplicates removed; order is kept.

    >>> parse_tags("env:prod, team:sdk team:sdk")
    ('env:prod', 'team:sdk')
    """
    if value is None:
        return ()
    items = [value] if isinstance(value, str) else list(value)
    out: list[str] = []
    for item in items:
        for tag in _TAG_SPLIT.split(item):
            if tag and tag not in out:
                out.append(tag)
    return tuple(out)


def _blank_to_none(value: str | None) -> str | None:
    if value is None:
        return None
    text = value.strip()
    return text or None


def _is_http_url(value: str) -> bool:
    try:
        parts = urlsplit(value)
    except ValueError:
        return False
    return parts.scheme in ("http", "https") and bool(parts.hostname)


@dataclasses.dataclass(frozen=True, repr=False)
class DatadogLogsConfig(BaseConfig):
    """Immutable settings for :class:`~grow_datadog_logs.DatadogLogsClient`.

    Common fields (timeouts, ``retry``, ``tls``, ``proxy``, ``headers``, ``user_agent_suffix``,
    ``hooks``, ``logger``) come from :class:`BaseConfig`; durations are float seconds
    (``datetime.timedelta`` accepted). ``api_key`` and ``app_key`` accept ``str`` and are stored
    as :class:`Secret`; ``repr()`` never shows them.

    The intake and API base URLs are derived from ``site``; ``url`` overrides both (tests,
    proxies, fakeintake) and ``intake_url`` / ``api_url`` override one each. After construction
    ``intake_url`` and ``api_url`` hold the resolved values and ``url`` is the given ``url`` or
    else the API base. Build a new config (instead of :func:`dataclasses.replace`) to change the
    site.

    ``validate()`` raises :class:`ConfigError` for an unknown site (unless both base URLs are
    set), non-http(s) URLs, a missing or blank ``api_key`` and a blank ``app_key``.
    """

    #: The common fields have no Datadog variable.
    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {}
    #: Datadog variables of the service fields, in precedence order.
    SERVICE_ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {
        "API_KEY": ("DD_API_KEY",),
        "APP_KEY": ("DD_APP_KEY", "DD_APPLICATION_KEY"),
        "SITE": ("DD_SITE",),
        "SERVICE": ("DD_SERVICE",),
        "ENV": ("DD_ENV",),
        "VERSION": ("DD_VERSION",),
        "TAGS": ("DD_TAGS",),
        "HOSTNAME": ("DD_HOSTNAME",),
    }

    #: Base URL for both the intake and the API (default: derived from ``site``).
    url: str = ""
    #: Datadog API key, header ``DD-API-KEY``; required by every call.
    api_key: Secret | None = None
    #: Datadog application key, header ``DD-APPLICATION-KEY``; query API only, never sent to
    #: the intake.
    app_key: Secret | None = None
    #: Datadog site, e.g. ``datadoghq.eu`` (default ``datadoghq.com``).
    site: str = DEFAULT_SITE
    #: Logs intake base URL (default: ``url``, else derived from the site).
    intake_url: str = ""
    #: API base URL (default: ``url``, else derived from the site).
    api_url: str = ""
    #: Default ``service`` of every log.
    service: str | None = None
    #: Environment, sent as tag ``env:<value>``.
    env: str | None = None
    #: Version, sent as tag ``version:<value>``.
    version: str | None = None
    #: Tags added to every log (a list or one string separated by commas or spaces).
    tags: Sequence[str] = ()
    #: Default ``hostname`` of every log.
    hostname: str | None = None
    #: ``ddsource`` of every log (default ``grow-sdk``).
    source: str = DEFAULT_SOURCE

    def __post_init__(self) -> None:
        super().__post_init__()
        site = normalize_site(self.site or DEFAULT_SITE)
        derived = resolve_site(site) if is_datadog_site(site) else None
        url = _blank_to_none(self.url)
        intake = _blank_to_none(self.intake_url) or url or (derived.intake_url if derived else "")
        api = _blank_to_none(self.api_url) or url or (derived.api_url if derived else "")
        set_ = object.__setattr__
        set_(self, "site", site)
        set_(self, "intake_url", intake)
        set_(self, "api_url", api)
        set_(self, "url", url or api)
        for name in ("service", "env", "version", "hostname"):
            set_(self, name, _blank_to_none(getattr(self, name)))
        set_(self, "source", _blank_to_none(self.source) or DEFAULT_SOURCE)
        set_(self, "tags", parse_tags(self.tags))

    def __repr__(self) -> str:
        text = safe_repr(self)
        for name in ("intake_url", "api_url"):
            value = getattr(self, name)
            text = text.replace(f"{name}={value!r}", f"{name}={redact_url(value)!r}")
        return text

    __str__ = __repr__

    @classmethod
    def _env_values(cls, reader: EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        vendor = cls.SERVICE_ENV_VENDOR
        for field, name in (("api_key", "API_KEY"), ("app_key", "APP_KEY")):
            secret = reader.string(name, vendor.get(name, ()))
            if secret is not None:
                values[field] = Secret(secret)
        for field, name in (
            ("site", "SITE"),
            ("intake_url", "INTAKE_URL"),
            ("api_url", "API_URL"),
            ("service", "SERVICE"),
            ("env", "ENV"),
            ("version", "VERSION"),
            ("tags", "TAGS"),
            ("hostname", "HOSTNAME"),
            ("source", "SOURCE"),
        ):
            value = reader.string(name, vendor.get(name, ()))
            if value is not None:
                values[field] = value
        return values

    @classmethod
    def from_env(
        cls: type[_C], environ: Mapping[str, str] | None = None, /, **overrides: Any
    ) -> _C:
        """Build from the environment, then validate.

        Precedence: explicit keyword > ``GROW_DATADOG_LOGS_<NAME>`` > Datadog variable
        (``DD_API_KEY``, ``DD_SITE`` …) > default. ``environ`` defaults to ``os.environ``; pass a
        dict in tests. Keyword overrides with the value ``None`` are ignored.
        """
        values = cls._env_values(EnvReader(environ))
        values.update({k: v for k, v in overrides.items() if v is not None})
        return cls(**values).validate()

    def validate(self: _C) -> _C:
        """Common checks plus the Datadog rules; raises :class:`ConfigError`. Returns ``self``."""
        if not self.intake_url or not self.api_url:
            raise ConfigError(
                f"config: unknown Datadog site {self.site!r} "
                "(set a known site or both intake_url and api_url)"
            )
        for name in ("intake_url", "api_url"):
            if not _is_http_url(getattr(self, name)):
                raise ConfigError(f"config: {name} must be an absolute http or https URL")
        super().validate()
        if self.api_key is None:
            raise ConfigError(
                "config: api_key is required (GROW_DATADOG_LOGS_API_KEY or DD_API_KEY)"
            )
        if not self.api_key.reveal().strip():
            raise ConfigError("config: api_key must not be blank")
        if self.app_key is not None and not self.app_key.reveal().strip():
            raise ConfigError("config: app_key must not be blank")
        return self
