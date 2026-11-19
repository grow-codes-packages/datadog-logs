// Service-owned: Datadog sites and the host resolver (API.md §1).

import { ConfigError } from "./core/index.js";

/** Default Datadog site (US1). */
export const DEFAULT_SITE = "datadoghq.com";

/**
 * Every Datadog site accepted by `site` / `DD_SITE` (the `servers` enum of the official OpenAPI
 * spec). The site is the API site, never the UI host (`app.datadoghq.com`).
 */
export const DATADOG_SITES: readonly string[] = Object.freeze([
  "datadoghq.com",
  "us3.datadoghq.com",
  "us5.datadoghq.com",
  "ap1.datadoghq.com",
  "ap2.datadoghq.com",
  "uk1.datadoghq.com",
  "datadoghq.eu",
  "ddog-gov.com",
  "us2.ddog-gov.com",
]);

/** The two base URLs of a Datadog site. */
export interface DatadogSiteUrls {
  /** Normalized site name. */
  site: string;
  /** Logs intake base, `https://http-intake.logs.<site>`. */
  intakeUrl: string;
  /** API base (validate, search, aggregate, indexes), `https://api.<site>`. */
  apiUrl: string;
}

/** Lower-cases and trims a site name. */
export function normalizeSite(site: string): string {
  return site.trim().toLowerCase();
}

/** True when `site` (after {@link normalizeSite}) is a known Datadog site. */
export function isDatadogSite(site: string): boolean {
  return DATADOG_SITES.includes(normalizeSite(site));
}

/**
 * Resolves a site name to its intake and API base URLs. Throws {@link ConfigError} for an unknown
 * site.
 *
 * @example
 * resolveSite("datadoghq.eu").intakeUrl // "https://http-intake.logs.datadoghq.eu"
 */
export function resolveSite(site: string = DEFAULT_SITE): DatadogSiteUrls {
  const normalized = normalizeSite(site);
  if (!DATADOG_SITES.includes(normalized)) {
    throw new ConfigError(
      `invalid configuration: unknown Datadog site ${JSON.stringify(site)} (expected one of ${DATADOG_SITES.join(", ")})`,
    );
  }
  return {
    site: normalized,
    intakeUrl: `https://http-intake.logs.${normalized}`,
    apiUrl: `https://api.${normalized}`,
  };
}
