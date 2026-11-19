// Service-owned: configuration of the Datadog Log Management client (API.md §2, DECISIONS 1).

import {
  type CommonConfig,
  type CommonConfigOptions,
  type CommonVendorEnv,
  ConfigError,
  type EnvMap,
  EnvReader,
  type Hooks,
  type ClientLogger,
  REDACTED,
  type RetryPolicy,
  Secret,
  type TlsOptions,
  describeCommonConfig,
  mergeOptions,
  readCommonEnv,
  redactUrl,
  resolveCommonConfig,
  secretValues,
  validateCommonConfig,
} from "./core/index.js";
import { DEFAULT_SITE, isDatadogSite, normalizeSite, resolveSite } from "./sites.js";

const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** Default `ddsource` sent with every log (DECISIONS 9). */
export const DEFAULT_SOURCE = "grow-sdk";

/** Options accepted by {@link DatadogLogsConfig} (all optional except `apiKey`). */
export interface DatadogLogsConfigOptions extends CommonConfigOptions {
  /**
   * Datadog API key, header `DD-API-KEY`; required by every call
   * (env `GROW_DATADOG_LOGS_API_KEY`, `DD_API_KEY`).
   */
  apiKey?: string | Secret;
  /**
   * Datadog application key, header `DD-APPLICATION-KEY`; needed only by the query API and never
   * sent to the intake (env `GROW_DATADOG_LOGS_APP_KEY`, `DD_APP_KEY`, `DD_APPLICATION_KEY`).
   */
  appKey?: string | Secret;
  /** Datadog site, e.g. `datadoghq.eu` (env `GROW_DATADOG_LOGS_SITE`, `DD_SITE`). Default `datadoghq.com`. */
  site?: string;
  /**
   * Base URL for both the intake and the API (tests, proxies, fakeintake). Overrides the site
   * (env `GROW_DATADOG_LOGS_URL`).
   */
  url?: string;
  /** Intake base URL override (env `GROW_DATADOG_LOGS_INTAKE_URL`). Default: `url`, else derived from the site. */
  intakeUrl?: string;
  /** API base URL override (env `GROW_DATADOG_LOGS_API_URL`). Default: `url`, else derived from the site. */
  apiUrl?: string;
  /** Default `service` of every log (env `GROW_DATADOG_LOGS_SERVICE`, `DD_SERVICE`). */
  service?: string;
  /** Environment, sent as tag `env:<v>` (env `GROW_DATADOG_LOGS_ENV`, `DD_ENV`). */
  env?: string;
  /** Version, sent as tag `version:<v>` (env `GROW_DATADOG_LOGS_VERSION`, `DD_VERSION`). */
  version?: string;
  /**
   * Tags added to every log: a list of `key:value` strings or one string separated by commas or
   * spaces (env `GROW_DATADOG_LOGS_TAGS`, `DD_TAGS`).
   */
  tags?: string | readonly string[];
  /** Default `hostname` of every log (env `GROW_DATADOG_LOGS_HOSTNAME`, `DD_HOSTNAME`). */
  hostname?: string;
  /** `ddsource` of every log (env `GROW_DATADOG_LOGS_SOURCE`). Default `grow-sdk`. */
  source?: string;
}

/** Vendor environment variables read after `GROW_DATADOG_LOGS_*`, in order. */
export const VENDOR_ENV: CommonVendorEnv & {
  apiKey: readonly string[];
  appKey: readonly string[];
  site: readonly string[];
  service: readonly string[];
  env: readonly string[];
  version: readonly string[];
  tags: readonly string[];
  hostname: readonly string[];
} = {
  url: [],
  apiKey: ["DD_API_KEY"],
  appKey: ["DD_APP_KEY", "DD_APPLICATION_KEY"],
  site: ["DD_SITE"],
  service: ["DD_SERVICE"],
  env: ["DD_ENV"],
  version: ["DD_VERSION"],
  tags: ["DD_TAGS"],
  hostname: ["DD_HOSTNAME"],
};

/**
 * Parses Datadog tags: `key:value` items separated by commas and/or whitespace (the `DD_TAGS`
 * convention). Empty items are dropped and duplicates removed, order kept.
 */
export function parseTags(value: string | readonly string[] | undefined): string[] {
  if (value === undefined) return [];
  const items = typeof value === "string" ? [value] : value;
  const out: string[] = [];
  for (const item of items) {
    for (const tag of item.split(/[\s,]+/)) {
      if (tag !== "" && !out.includes(tag)) out.push(tag);
    }
  }
  return out;
}

function isHttpUrl(value: string): boolean {
  try {
    const u = new URL(value);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}

function blankToUndefined(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}

/** Immutable configuration of {@link DatadogLogsClient}. */
export class DatadogLogsConfig implements CommonConfig {
  /** Default site. */
  static readonly DEFAULT_SITE = DEFAULT_SITE;
  /** Prefix of every environment variable. */
  static readonly ENV_PREFIX = "GROW_DATADOG_LOGS_";

  /** `url` when given, else {@link apiUrl} (used for relative paths). */
  readonly url: string;
  readonly connectTimeoutMs: number;
  readonly requestTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly retry: RetryPolicy;
  readonly tls: Readonly<TlsOptions>;
  readonly proxy?: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly userAgentSuffix?: string;
  readonly hooks?: Hooks;
  readonly logger?: ClientLogger;
  /** Datadog API key. */
  readonly apiKey?: Secret;
  /** Datadog application key (query API only). */
  readonly appKey?: Secret;
  /** Normalized Datadog site. */
  readonly site: string;
  /** Logs intake base URL. */
  readonly intakeUrl: string;
  /** API base URL. */
  readonly apiUrl: string;
  /** Default `service`. */
  readonly service?: string;
  /** Environment (`env:` tag). */
  readonly env?: string;
  /** Version (`version:` tag). */
  readonly version?: string;
  /** Tags added to every log. */
  readonly tags: readonly string[];
  /** Default `hostname`. */
  readonly hostname?: string;
  /** `ddsource` of every log. */
  readonly source: string;

  /** Applies defaults, validates (throws {@link ConfigError}) and freezes. */
  constructor(options: DatadogLogsConfigOptions = {}) {
    this.site = normalizeSite(options.site ?? DEFAULT_SITE);
    const derived = isDatadogSite(this.site) ? resolveSite(this.site) : undefined;
    const url = blankToUndefined(options.url);
    this.intakeUrl = blankToUndefined(options.intakeUrl) ?? url ?? derived?.intakeUrl ?? "";
    this.apiUrl = blankToUndefined(options.apiUrl) ?? url ?? derived?.apiUrl ?? "";
    const common = resolveCommonConfig(
      { ...options, ...(url === undefined ? { url: undefined } : { url }) },
      this.apiUrl === "" ? "https://api.invalid" : this.apiUrl,
    );
    this.url = common.url;
    this.connectTimeoutMs = common.connectTimeoutMs;
    this.requestTimeoutMs = common.requestTimeoutMs;
    this.totalTimeoutMs = common.totalTimeoutMs;
    this.retry = common.retry;
    this.tls = common.tls;
    this.headers = common.headers;
    if (common.proxy !== undefined) this.proxy = common.proxy;
    if (common.userAgentSuffix !== undefined) this.userAgentSuffix = common.userAgentSuffix;
    if (common.hooks !== undefined) this.hooks = common.hooks;
    if (common.logger !== undefined) this.logger = common.logger;
    const apiKey = Secret.from(options.apiKey);
    if (apiKey !== undefined) this.apiKey = apiKey;
    const appKey = Secret.from(options.appKey);
    if (appKey !== undefined) this.appKey = appKey;
    const service = blankToUndefined(options.service);
    if (service !== undefined) this.service = service;
    const env = blankToUndefined(options.env);
    if (env !== undefined) this.env = env;
    const version = blankToUndefined(options.version);
    if (version !== undefined) this.version = version;
    const hostname = blankToUndefined(options.hostname);
    if (hostname !== undefined) this.hostname = hostname;
    this.tags = Object.freeze(parseTags(options.tags));
    this.source = blankToUndefined(options.source) ?? DEFAULT_SOURCE;
    this.validate();
    Object.freeze(this);
  }

  /** Returns `options` when it already is a config, otherwise builds one. */
  static from(options: DatadogLogsConfigOptions = {}): DatadogLogsConfig {
    if (options instanceof DatadogLogsConfig) return options;
    return new DatadogLogsConfig(options);
  }

  /**
   * Builds a config from the environment. Precedence: explicit `overrides` >
   * `GROW_DATADOG_LOGS_<NAME>` > vendor variables (`DD_API_KEY`, `DD_SITE`, … see
   * {@link VENDOR_ENV}) > defaults.
   */
  static fromEnv(
    overrides: DatadogLogsConfigOptions = {},
    env: EnvMap = process.env,
  ): DatadogLogsConfig {
    const reader = new EnvReader(DatadogLogsConfig.ENV_PREFIX, env);
    const fromEnv: DatadogLogsConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    const apiKey = reader.secret("API_KEY", VENDOR_ENV.apiKey);
    if (apiKey !== undefined) fromEnv.apiKey = apiKey;
    const appKey = reader.secret("APP_KEY", VENDOR_ENV.appKey);
    if (appKey !== undefined) fromEnv.appKey = appKey;
    const strings = [
      ["site", "SITE", VENDOR_ENV.site],
      ["intakeUrl", "INTAKE_URL", []],
      ["apiUrl", "API_URL", []],
      ["service", "SERVICE", VENDOR_ENV.service],
      ["env", "ENV", VENDOR_ENV.env],
      ["version", "VERSION", VENDOR_ENV.version],
      ["tags", "TAGS", VENDOR_ENV.tags],
      ["hostname", "HOSTNAME", VENDOR_ENV.hostname],
      ["source", "SOURCE", []],
    ] as const;
    for (const [field, name, vendor] of strings) {
      const value = reader.string(name, vendor);
      if (value !== undefined) fromEnv[field] = value;
    }
    const merged = mergeOptions<DatadogLogsConfigOptions>(fromEnv, overrides);
    return new DatadogLogsConfig(merged);
  }

  /**
   * Throws {@link ConfigError} when a setting is invalid: unknown site (unless both base URLs are
   * set), non-http(s) URLs, missing or blank API key, blank application key.
   */
  validate(): void {
    validateCommonConfig(this);
    const problems: string[] = [];
    if (this.intakeUrl === "" || this.apiUrl === "") {
      problems.push(
        `unknown Datadog site ${JSON.stringify(this.site)} (set a known site or both intakeUrl and apiUrl)`,
      );
    } else {
      if (!isHttpUrl(this.intakeUrl)) problems.push("intakeUrl must be an http or https URL");
      if (!isHttpUrl(this.apiUrl)) problems.push("apiUrl must be an http or https URL");
    }
    if (this.apiKey === undefined) {
      problems.push("apiKey is required (GROW_DATADOG_LOGS_API_KEY or DD_API_KEY)");
    } else if (this.apiKey.reveal().trim() === "") {
      problems.push("apiKey must not be blank");
    }
    if (this.appKey?.reveal().trim() === "") problems.push("appKey must not be blank");
    if (problems.length > 0) throw new ConfigError(`invalid configuration: ${problems.join("; ")}`);
  }

  /** Credential values to redact from error messages and logs. */
  knownSecrets(): string[] {
    return secretValues(this.apiKey, this.appKey);
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    const out: Record<string, unknown> = {
      ...describeCommonConfig(this),
      site: this.site,
      intakeUrl: redactUrl(this.intakeUrl),
      apiUrl: redactUrl(this.apiUrl),
      apiKey: this.apiKey === undefined ? null : REDACTED,
      appKey: this.appKey === undefined ? null : REDACTED,
      tags: [...this.tags],
      source: this.source,
    };
    for (const key of ["service", "env", "version", "hostname"] as const) {
      if (this[key] !== undefined) out[key] = this[key];
    }
    return out;
  }

  toString(): string {
    return `DatadogLogsConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
