"""Datadog sites and the host resolver (service code).

Every Datadog site has two hosts: the logs intake ``https://http-intake.logs.<site>`` and the API
``https://api.<site>`` (validate, search, aggregate, indexes). The site is the API site, never
the UI host (``app.datadoghq.com``).
"""

from __future__ import annotations

import dataclasses

from ._core import ConfigError

__all__ = [
    "DATADOG_SITES",
    "DEFAULT_SITE",
    "DatadogSiteUrls",
    "is_datadog_site",
    "normalize_site",
    "resolve_site",
]

#: Default Datadog site (US1).
DEFAULT_SITE = "datadoghq.com"

#: Every site accepted by ``site`` / ``DD_SITE`` (the official OpenAPI ``servers`` enum).
DATADOG_SITES: tuple[str, ...] = (
    "datadoghq.com",
    "us3.datadoghq.com",
    "us5.datadoghq.com",
    "ap1.datadoghq.com",
    "ap2.datadoghq.com",
    "uk1.datadoghq.com",
    "datadoghq.eu",
    "ddog-gov.com",
    "us2.ddog-gov.com",
)


@dataclasses.dataclass(frozen=True)
class DatadogSiteUrls:
    """The two base URLs of a Datadog site."""

    #: Normalized site name.
    site: str
    #: Logs intake base, ``https://http-intake.logs.<site>``.
    intake_url: str
    #: API base, ``https://api.<site>``.
    api_url: str


def normalize_site(site: str) -> str:
    """Lower-case and trim a site name."""
    return site.strip().lower()


def is_datadog_site(site: str) -> bool:
    """``True`` when ``site`` (after :func:`normalize_site`) is a known Datadog site."""
    return normalize_site(site) in DATADOG_SITES


def resolve_site(site: str = DEFAULT_SITE) -> DatadogSiteUrls:
    """Resolve a site name to its intake and API base URLs.

    Raises :class:`ConfigError` for an unknown site.

    >>> resolve_site("datadoghq.eu").intake_url
    'https://http-intake.logs.datadoghq.eu'
    """
    normalized = normalize_site(site)
    if normalized not in DATADOG_SITES:
        raise ConfigError(
            f"config: unknown Datadog site {site!r} (expected one of {', '.join(DATADOG_SITES)})"
        )
    return DatadogSiteUrls(
        site=normalized,
        intake_url=f"https://http-intake.logs.{normalized}",
        api_url=f"https://api.{normalized}",
    )
