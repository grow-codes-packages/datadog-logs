// Service-owned: the Datadog Log Management client (API.md §4; DECISIONS 2, 3).

import type { Dispatcher } from "undici";
import {
  ConfigError,
  DatadogLogsError,
  type HealthStatus,
  HttpTransport,
  type TransportOptions,
  UnavailableError,
  UnsupportedError,
  createDispatcher,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { DatadogLogsConfig, type DatadogLogsConfigOptions } from "./config.js";
import { DatadogLogExporter, type DatadogLogExporterOptions } from "./exporter.js";
import { datadogRequest, joinUrl, rateLimitInterceptor } from "./http.js";
import {
  BatchingLogClient,
  type Attributes,
  type BatchingLogClientOptions,
} from "./logging/index.js";
import { DatadogLogsQuery } from "./query.js";

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Test seams (undici `MockAgent`, fake sleep). Not part of the stable API. */
export type ClientInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

/** API key validation path. */
export const VALIDATE_PATH = "/api/v1/validate";

/** Options of {@link DatadogLogsClient.logClient}. */
export interface DatadogLogClientOptions extends BatchingLogClientOptions {
  /** Intake request limits (capped at the Datadog limits). */
  exporter?: DatadogLogExporterOptions;
}

/**
 * {@link LogClient} that ships records to the Datadog logs intake through a
 * {@link DatadogLogExporter} and a background `BatchProcessor`. Create it with
 * {@link DatadogLogsClient.logClient}.
 */
export class DatadogLogClient extends BatchingLogClient {
  /** The exporter used by this client. */
  readonly exporter: DatadogLogExporter;

  /** @param exporter the intake exporter. @param options batching, resource, redaction. */
  constructor(exporter: DatadogLogExporter, options: BatchingLogClientOptions = {}) {
    super(exporter, options);
    this.exporter = exporter;
  }
}

/**
 * Client for Datadog Log Management: API key checks, the logs intake (`logClient`, `exporter`)
 * and the query API (`query`, needs `appKey`).
 *
 * @example
 * const client = DatadogLogsClient.fromEnv();
 * const logs = client.logClient({ resource: { "service.name": "checkout" } });
 * logs.logger().info("user signed in", { user: { id: 42 } });
 * await client.close();
 */
export class DatadogLogsClient {
  /** The immutable configuration. */
  readonly config: DatadogLogsConfig;
  readonly #transport: HttpTransport;
  readonly #ownedDispatcher: Dispatcher | undefined;
  readonly #logClients = new Set<DatadogLogClient>();
  #exporter: DatadogLogExporter | undefined;
  #query: DatadogLogsQuery | undefined;
  #closing: Promise<void> | undefined;

  /** Accepts a {@link DatadogLogsConfig} or plain options. Throws `ConfigError` when invalid. */
  constructor(config: DatadogLogsConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = DatadogLogsConfig.from(config);
    this.config = cfg;
    let base: Dispatcher;
    if (internals.dispatcher !== undefined) {
      base = internals.dispatcher;
    } else {
      try {
        base = createDispatcher(cfg);
      } catch (err) {
        throw new ConfigError("invalid configuration: cannot read a TLS file", { cause: err });
      }
      this.#ownedDispatcher = base;
    }
    this.#transport = new HttpTransport({
      ...internals,
      dispatcher: base.compose(rateLimitInterceptor),
      config: cfg,
      knownSecrets: () => cfg.knownSecrets(),
    });
  }

  /** Builds a client from `GROW_DATADOG_LOGS_*`, `DD_API_KEY`, `DD_SITE`, … environment variables. */
  static fromEnv(overrides: DatadogLogsConfigOptions = {}): DatadogLogsClient {
    return new DatadogLogsClient(DatadogLogsConfig.fromEnv(overrides));
  }

  /** The shared HTTP transport (for service modules). @internal */
  get transport(): HttpTransport {
    return this.#transport;
  }

  #validate(operation: string, allowStatus: number[], options: CallOptions) {
    return datadogRequest<Record<string, unknown> | undefined>(this.#transport, {
      method: "GET",
      path: joinUrl(this.config.apiUrl, VALIDATE_PATH),
      headers: { "dd-api-key": this.config.apiKey?.reveal() ?? "" },
      operation,
      allowStatus,
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });
  }

  /**
   * Reachability through `GET /api/v1/validate` (Datadog has no health endpoint). 2xx → healthy
   * (`details.apiKeyValid`); 401/403 → `healthy=false, status="unauthorized"`; 429 → `degraded`;
   * 5xx → `unhealthy`; network errors → `unreachable`. Never throws except `CANCELLED`/`CONFIG`.
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      try {
        const res = await this.#validate("health", [401, 403], options);
        if (res.status === 401 || res.status === 403) {
          return healthStatus(
            "unauthorized",
            { httpStatus: res.status, apiKeyValid: false },
            false,
          );
        }
        return healthStatus("ok", { apiKeyValid: res.data?.valid === true }, true);
      } catch (err) {
        if (err instanceof DatadogLogsError && err.httpStatus === 429) {
          return healthStatus("degraded", { httpStatus: 429, error: err.code }, false);
        }
        throw err;
      }
    });
  }

  /**
   * Authenticated round trip `GET /api/v1/validate`; returns the latency in milliseconds. 403 →
   * `AuthenticationError`; a 2xx without `{"valid": true}` → `UnavailableError`.
   */
  async ping(options: CallOptions = {}): Promise<number> {
    const start = performance.now();
    const res = await this.#validate("ping", [], options);
    if (res.data?.valid !== true) {
      throw new UnavailableError("ping: unexpected response from /api/v1/validate", {
        retryable: false,
        httpStatus: res.status,
      });
    }
    return performance.now() - start;
  }

  /**
   * Whether the API key is valid (`GET /api/v1/validate`): `true` for `{"valid": true}`, `false`
   * for 403. Other failures throw the mapped error.
   */
  async validateApiKey(options: CallOptions = {}): Promise<boolean> {
    const res = await this.#validate("validateApiKey", [403], options);
    if (res.status === 403) return false;
    const valid = res.data?.valid;
    if (typeof valid !== "boolean") {
      throw new UnavailableError("validateApiKey: unexpected response from /api/v1/validate", {
        retryable: false,
        httpStatus: res.status,
      });
    }
    return valid;
  }

  /** Datadog is a SaaS without a version endpoint: always throws `UnsupportedError`. */
  serverVersion(_options: CallOptions = {}): Promise<string> {
    return Promise.reject(
      new UnsupportedError("serverVersion: Datadog does not report a server version"),
    );
  }

  /** The intake exporter (one per client, default limits). */
  get exporter(): DatadogLogExporter {
    this.#exporter ??= new DatadogLogExporter(this.#transport, this.config);
    return this.#exporter;
  }

  /**
   * A batching {@link DatadogLogClient}. The resource defaults to the configured `service`,
   * `version`, `env` and `hostname`. {@link close} flushes and closes every log client it created.
   */
  logClient(options: DatadogLogClientOptions = {}): DatadogLogClient {
    if (this.#closing !== undefined) throw new ConfigError("logClient: client is closed");
    const { exporter: limits, ...batching } = options;
    const exporter =
      limits === undefined
        ? this.exporter
        : new DatadogLogExporter(this.#transport, this.config, limits);
    const resource: Attributes = {};
    if (this.config.service !== undefined) resource["service.name"] = this.config.service;
    if (this.config.version !== undefined) resource["service.version"] = this.config.version;
    if (this.config.env !== undefined) resource["deployment.environment"] = this.config.env;
    if (this.config.hostname !== undefined) resource["host.name"] = this.config.hostname;
    const client = new DatadogLogClient(exporter, {
      ...batching,
      resource: { ...resource, ...(batching.resource ?? {}) },
    });
    this.#logClients.add(client);
    return client;
  }

  /** The query client (search, aggregate, indexes). Throws `ConfigError` without `appKey`. */
  query(): DatadogLogsQuery {
    if (this.config.appKey === undefined) {
      throw new ConfigError(
        "invalid configuration: appKey is required for the query API (GROW_DATADOG_LOGS_APP_KEY or DD_APP_KEY)",
      );
    }
    this.#query ??= new DatadogLogsQuery(this.#transport, this.config);
    return this.#query;
  }

  /** Flushes and closes every log client, then releases connections. Idempotent. */
  close(): Promise<void> {
    this.#closing ??= (async () => {
      await Promise.allSettled([...this.#logClients].map((c) => c.close()));
      this.#logClients.clear();
      await this.#transport.close();
      await this.#ownedDispatcher?.close().catch(() => undefined);
    })();
    return this.#closing;
  }
}
