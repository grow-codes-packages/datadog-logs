// Service-owned: Datadog Logs search / aggregate / indexes (API.md §6; DECISIONS 3, 5, 8).

import {
  type HttpTransport,
  UnavailableError,
  UnsupportedError,
  ValidationError,
} from "./core/index.js";
import type { DatadogLogsConfig } from "./config.js";
import { datadogRequest, joinUrl } from "./http.js";
import {
  BaseLogQueryClient,
  type LogRecord,
  type LogSearchOptions,
  type LogSearchResult,
  type TailHandle,
  type TailOptions,
  type WaitForLogOptions,
} from "./logging/index.js";
import { eventToLogRecord, parseLogEvent, type DatadogLogEvent } from "./mapping.js";

/** Search path (`POST`). */
export const SEARCH_PATH = "/api/v2/logs/events/search";
/** Aggregate path (`POST`). */
export const AGGREGATE_PATH = "/api/v2/logs/analytics/aggregate";
/** Indexes path (`GET`). */
export const INDEXES_PATH = "/api/v1/logs/config/indexes";
/** Largest `page.limit` accepted by the search API. */
export const MAX_PAGE_LIMIT = 5000;
/** Page size used by {@link DatadogLogsQuery.iterateLogs} when none is given. */
export const DEFAULT_ITERATOR_PAGE_SIZE = 1000;
/** Default poll interval of `tail` and `waitForLog` (Datadog's search rate limit is low). */
export const DEFAULT_POLL_INTERVAL_MS = 5000;

/** Time value accepted by the Datadog API: a `Date`, epoch milliseconds or a string (ISO-8601 or date math such as `now-15m`). */
export type DatadogTime = Date | number | string;

/** Where the search runs. */
export type StorageTier = "indexes" | "online-archives" | "flex";

/** Filter shared by search and aggregate. */
export interface DatadogLogsFilter {
  /** Datadog search query (default `*`). */
  query?: string;
  /** Window start (default `now-15m`). */
  from?: DatadogTime;
  /** Window end (default `now`). */
  to?: DatadogTime;
  /** Indexes to search (default `["*"]`). */
  indexes?: readonly string[];
  /** Storage tier (default `indexes`). */
  storageTier?: StorageTier;
}

/** Per-call options. */
export interface QueryCallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Parameters of {@link DatadogLogsQuery.searchLogs}. */
export interface SearchLogsParams extends DatadogLogsFilter, QueryCallOptions {
  /**
   * `searchLogs`: records in this page (1..5000, default 100).
   * `iterateLogs`: total records to yield (default: all).
   */
  limit?: number;
  /** `iterateLogs` only: records per request (1..5000, default min(limit, 1000)). */
  pageSize?: number;
  /** `timestamp` (oldest first) or `-timestamp` (newest first, default). */
  sort?: "timestamp" | "-timestamp";
  /** Cursor from {@link DatadogLogsPage.nextCursor}; send the same filter and sort with it. */
  cursor?: string;
}

/** A non-fatal warning of a query (results may be partial). */
export interface DatadogWarning {
  code?: string;
  title?: string;
  detail?: string;
}

/** Response metadata shared by search and aggregate. */
export interface DatadogQueryMeta {
  /** Cursor of the next page; absent on the last page. */
  nextCursor?: string;
  /** `done` or `timeout` (partial results). */
  status?: string;
  /** Non-fatal warnings (e.g. `unknown_index`). */
  warnings: DatadogWarning[];
  /** `meta.request_id`. */
  requestId?: string;
  /** Server time in ms (`meta.elapsed`). */
  elapsedMs?: number;
}

/** One page of {@link DatadogLogsQuery.searchLogs}. */
export interface DatadogLogsPage extends DatadogQueryMeta {
  /** Events as returned by Datadog (flattened). */
  events: DatadogLogEvent[];
  /** The same events normalized to {@link LogRecord}. */
  records: LogRecord[];
}

/** Result of {@link DatadogLogsQuery.search}: the category result plus Datadog metadata. */
export interface DatadogLogSearchResult extends LogSearchResult {
  /** Non-fatal warnings (results may be partial). */
  warnings: DatadogWarning[];
  /** `done` or `timeout`. */
  status?: string;
  /** `meta.request_id`. */
  requestId?: string;
}

/** Aggregation functions. */
export type DatadogAggregation =
  | "count"
  | "cardinality"
  | "pc75"
  | "pc90"
  | "pc95"
  | "pc98"
  | "pc99"
  | "sum"
  | "min"
  | "max"
  | "avg"
  | "median";

/** One computation of {@link DatadogLogsQuery.aggregateLogs} (results are keyed `c0`, `c1`, … in order). */
export interface DatadogCompute {
  aggregation: DatadogAggregation;
  /** `total` (default) or `timeseries`. */
  type?: "total" | "timeseries";
  /** Time bucket for `timeseries`, e.g. `5m`. */
  interval?: string;
  /** Measure for non-count aggregations, e.g. `@duration`. */
  metric?: string;
}

/** Sort of a group-by. */
export interface DatadogGroupBySort {
  aggregation?: DatadogAggregation;
  order?: "asc" | "desc";
  type?: "alphabetical" | "measure";
  metric?: string;
}

/** One group-by facet of {@link DatadogLogsQuery.aggregateLogs}. */
export interface DatadogGroupBy {
  /** Facet, e.g. `service` or `@http.status_code`. */
  facet: string;
  /** Maximum buckets (Datadog default 10). */
  limit?: number;
  sort?: DatadogGroupBySort;
  /** Value used for logs without the facet. */
  missing?: string | number;
  /** Adds a total bucket (`true` or its label). */
  total?: boolean | string | number;
  /** Numeric histogram buckets. */
  histogram?: { interval: number; min: number; max: number };
}

/** Options of {@link DatadogLogsQuery.aggregateLogs}. */
export interface AggregateLogsOptions extends DatadogLogsFilter, QueryCallOptions {
  /** Cursor from {@link DatadogAggregateResult.nextCursor}. */
  cursor?: string;
}

/** One point of a timeseries compute. */
export interface DatadogTimeseriesPoint {
  time: Date;
  value: number;
}

/** A compute value: a number, a string, or a timeseries. */
export type DatadogComputeValue = number | string | DatadogTimeseriesPoint[];

/** One bucket of an aggregation. */
export interface DatadogBucket {
  /** Facet values of the bucket (`{}` without group-by). */
  by: Record<string, string | number>;
  /** Results keyed `c0`, `c1`, … in compute order. */
  computes: Record<string, DatadogComputeValue>;
}

/** Result of {@link DatadogLogsQuery.aggregateLogs}. */
export interface DatadogAggregateResult extends DatadogQueryMeta {
  buckets: DatadogBucket[];
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Converts a {@link DatadogTime} into the string the API expects (ISO-8601 for dates/numbers). */
export function formatDatadogTime(value: DatadogTime): string {
  if (value instanceof Date) {
    if (Number.isNaN(value.getTime())) throw new ValidationError("logs.query: invalid date");
    return value.toISOString();
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new ValidationError("logs.query: invalid epoch time");
    return new Date(value).toISOString();
  }
  if (value.trim() === "") throw new ValidationError("logs.query: time must not be empty");
  return value.trim();
}

function isComputeList(
  value: DatadogCompute | readonly DatadogCompute[],
): value is readonly DatadogCompute[] {
  return Array.isArray(value);
}

function checkLimit(value: number | undefined, op: string, name = "limit"): void {
  if (value !== undefined && (!Number.isInteger(value) || value < 1 || value > MAX_PAGE_LIMIT)) {
    throw new ValidationError(`${op}: ${name} must be an integer 1..${MAX_PAGE_LIMIT}`);
  }
}

function filterBody(f: DatadogLogsFilter): Record<string, unknown> {
  const query = f.query === undefined || f.query.trim() === "" ? "*" : f.query;
  return {
    query,
    from: formatDatadogTime(f.from ?? "now-15m"),
    to: formatDatadogTime(f.to ?? "now"),
    indexes: f.indexes === undefined || f.indexes.length === 0 ? ["*"] : [...f.indexes],
    storage_tier: f.storageTier ?? "indexes",
  };
}

function parseMeta(body: Record<string, unknown>, headerRequestId?: string): DatadogQueryMeta {
  const meta = isObject(body.meta) ? body.meta : {};
  const out: DatadogQueryMeta = { warnings: [] };
  const page = isObject(meta.page) ? meta.page : {};
  if (typeof page.after === "string" && page.after !== "") out.nextCursor = page.after;
  if (typeof meta.status === "string") out.status = meta.status;
  if (Array.isArray(meta.warnings)) {
    for (const w of meta.warnings) {
      if (!isObject(w)) continue;
      const warning: DatadogWarning = {};
      for (const key of ["code", "title", "detail"] as const) {
        if (typeof w[key] === "string") warning[key] = w[key];
      }
      out.warnings.push(warning);
    }
  }
  const requestId = typeof meta.request_id === "string" ? meta.request_id : headerRequestId;
  if (requestId !== undefined) out.requestId = requestId;
  if (typeof meta.elapsed === "number") out.elapsedMs = meta.elapsed;
  return out;
}

function parseComputeValue(value: unknown): DatadogComputeValue | undefined {
  if (typeof value === "number" || typeof value === "string") return value;
  if (!Array.isArray(value)) return undefined;
  const points: DatadogTimeseriesPoint[] = [];
  for (const p of value) {
    if (!isObject(p) || typeof p.time !== "string" || typeof p.value !== "number") continue;
    points.push({ time: new Date(p.time), value: p.value });
  }
  return points;
}

/**
 * Datadog Logs query client (needs `appKey`): `searchLogs` (one page), `iterateLogs` (cursor
 * pagination), `aggregateLogs`, and the category {@link BaseLogQueryClient} methods: `search`,
 * `waitForLog`, `tail` (polling search), `listStreams` (index names, needs `logs_read_config`);
 * `listLabels` throws `UnsupportedError`.
 */
export class DatadogLogsQuery extends BaseLogQueryClient {
  readonly #transport: HttpTransport;
  readonly #config: DatadogLogsConfig;

  /** Use `DatadogLogsClient.query()`. @internal */
  constructor(transport: HttpTransport, config: DatadogLogsConfig) {
    super();
    this.#transport = transport;
    this.#config = config;
  }

  #headers(): Record<string, string> {
    return {
      "dd-api-key": this.#config.apiKey?.reveal() ?? "",
      "dd-application-key": this.#config.appKey?.reveal() ?? "",
    };
  }

  async #post(path: string, operation: string, json: unknown, signal?: AbortSignal) {
    const res = await datadogRequest<unknown>(this.#transport, {
      method: "POST",
      path: joinUrl(this.#config.apiUrl, path),
      headers: this.#headers(),
      json,
      // read-only POSTs: safe to retry on 5xx
      idempotent: true,
      operation,
      ...(signal === undefined ? {} : { signal }),
    });
    if (!isObject(res.data)) {
      throw new UnavailableError(`${operation}: unexpected response (no JSON object)`, {
        retryable: false,
        httpStatus: res.status,
      });
    }
    return { body: res.data, requestId: res.requestId };
  }

  /**
   * One page of `POST /api/v2/logs/events/search`. Pass `nextCursor` back as `cursor` (with the
   * same filter and sort) for the next page.
   *
   * @example
   * const page = await query.searchLogs({ query: 'service:checkout "run-7f3a"', from: "now-1h", limit: 50 });
   */
  async searchLogs(params: SearchLogsParams = {}): Promise<DatadogLogsPage> {
    const op = "logs.searchLogs";
    checkLimit(params.limit, op);
    const sort: string = params.sort ?? "-timestamp";
    if (sort !== "timestamp" && sort !== "-timestamp") {
      throw new ValidationError(`${op}: sort must be "timestamp" or "-timestamp"`);
    }
    const page: Record<string, unknown> = { limit: params.limit ?? 100 };
    if (params.cursor !== undefined && params.cursor !== "") page.cursor = params.cursor;
    const { body, requestId } = await this.#post(
      SEARCH_PATH,
      op,
      { filter: filterBody(params), sort, page },
      params.signal,
    );
    if (!Array.isArray(body.data)) {
      throw new UnavailableError(`${op}: unexpected response (no data array)`, {
        retryable: false,
        httpStatus: 200,
      });
    }
    const events = body.data
      .map((item) => parseLogEvent(item))
      .filter((e): e is DatadogLogEvent => e !== undefined);
    return {
      ...parseMeta(body, requestId),
      events,
      records: events.map(eventToLogRecord),
    };
  }

  /**
   * Iterates over every matching event across pages (cursor pagination). `limit` caps the total
   * number of events; a repeated cursor raises `UnavailableError` (protocol loop guard).
   *
   * @example
   * for await (const event of query.iterateLogs({ query: "status:error", limit: 5000 })) console.log(event.message);
   */
  async *iterateLogs(params: SearchLogsParams = {}): AsyncGenerator<DatadogLogEvent, void, void> {
    const op = "logs.iterateLogs";
    if (params.limit !== undefined && (!Number.isInteger(params.limit) || params.limit < 1)) {
      throw new ValidationError(`${op}: limit must be a positive integer`);
    }
    checkLimit(params.pageSize, op, "pageSize");
    const pageSize =
      params.pageSize ??
      Math.min(params.limit ?? DEFAULT_ITERATOR_PAGE_SIZE, DEFAULT_ITERATOR_PAGE_SIZE);
    let remaining = params.limit ?? Number.POSITIVE_INFINITY;
    let cursor = params.cursor;
    const seen = new Set<string>();
    while (remaining > 0) {
      const page = await this.searchLogs({
        ...params,
        limit: Math.min(pageSize, remaining),
        ...(cursor === undefined ? {} : { cursor }),
      });
      for (const event of page.events) {
        if (remaining <= 0) return;
        remaining--;
        yield event;
      }
      if (page.nextCursor === undefined || page.events.length === 0) return;
      if (seen.has(page.nextCursor)) {
        throw new UnavailableError(`${op}: the service repeated a page cursor`, {
          retryable: false,
        });
      }
      seen.add(page.nextCursor);
      cursor = page.nextCursor;
    }
  }

  /**
   * `POST /api/v2/logs/analytics/aggregate`: computes `compute` (results keyed `c0`, `c1`, …) over
   * the logs matching the filter, optionally grouped by facets.
   *
   * @example
   * const r = await query.aggregateLogs({ aggregation: "count" }, [{ facet: "service" }], { query: "env:prod" });
   */
  async aggregateLogs(
    compute: DatadogCompute | readonly DatadogCompute[],
    groupBy: readonly DatadogGroupBy[] = [],
    options: AggregateLogsOptions = {},
  ): Promise<DatadogAggregateResult> {
    const op = "logs.aggregateLogs";
    const computes: readonly DatadogCompute[] = isComputeList(compute) ? compute : [compute];
    if (computes.length === 0) throw new ValidationError(`${op}: compute must not be empty`);
    const wireCompute = computes.map((c) => {
      if ((c.aggregation as string | undefined) === undefined || (c.aggregation as string) === "") {
        throw new ValidationError(`${op}: compute.aggregation is required`);
      }
      return {
        aggregation: c.aggregation,
        type: c.type ?? "total",
        ...(c.interval === undefined ? {} : { interval: c.interval }),
        ...(c.metric === undefined ? {} : { metric: c.metric }),
      };
    });
    const wireGroupBy = groupBy.map((g) => {
      if (typeof g.facet !== "string" || g.facet.trim() === "") {
        throw new ValidationError(`${op}: groupBy.facet is required`);
      }
      const out: Record<string, unknown> = { facet: g.facet };
      for (const key of ["limit", "sort", "missing", "total", "histogram"] as const) {
        if (g[key] !== undefined) out[key] = g[key];
      }
      return out;
    });
    const json: Record<string, unknown> = { compute: wireCompute, filter: filterBody(options) };
    if (wireGroupBy.length > 0) json.group_by = wireGroupBy;
    if (options.cursor !== undefined && options.cursor !== "")
      json.page = { cursor: options.cursor };
    const { body, requestId } = await this.#post(AGGREGATE_PATH, op, json, options.signal);
    const data = isObject(body.data) ? body.data : undefined;
    if (data === undefined || !Array.isArray(data.buckets)) {
      throw new UnavailableError(`${op}: unexpected response (no data.buckets)`, {
        retryable: false,
        httpStatus: 200,
      });
    }
    const buckets: DatadogBucket[] = [];
    for (const b of data.buckets) {
      if (!isObject(b)) continue;
      const by: Record<string, string | number> = {};
      if (isObject(b.by)) {
        for (const [k, v] of Object.entries(b.by)) {
          if (typeof v === "string" || typeof v === "number") by[k] = v;
        }
      }
      const computesOut: Record<string, DatadogComputeValue> = {};
      if (isObject(b.computes)) {
        for (const [k, v] of Object.entries(b.computes)) {
          const parsed = parseComputeValue(v);
          if (parsed !== undefined) computesOut[k] = parsed;
        }
      }
      buckets.push({ by, computes: computesOut });
    }
    return { ...parseMeta(body, requestId), buckets };
  }

  /**
   * Category search: one page of {@link searchLogs} (`start`/`end` → `from`/`to`, `order` →
   * `sort`, `cursor` → `page.cursor`). `total` is never set (Datadog returns none).
   */
  async search(query: string, opts: LogSearchOptions = {}): Promise<DatadogLogSearchResult> {
    const page = await this.searchLogs({
      query,
      limit: opts.limit ?? 100,
      sort: opts.order === "asc" ? "timestamp" : "-timestamp",
      ...(opts.start === undefined ? {} : { from: opts.start }),
      ...(opts.end === undefined ? {} : { to: opts.end }),
      ...(opts.cursor === undefined ? {} : { cursor: opts.cursor }),
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    const result: DatadogLogSearchResult = { records: page.records, warnings: page.warnings };
    if (page.nextCursor !== undefined) result.nextCursor = page.nextCursor;
    if (page.status !== undefined) result.status = page.status;
    if (page.requestId !== undefined) result.requestId = page.requestId;
    return result;
  }

  /** Index names from `GET /api/v1/logs/config/indexes` (needs the `logs_read_config` permission). */
  override async listStreams(): Promise<string[]> {
    return (await this.listIndexes()).map((i) => i.name);
  }

  /**
   * Log indexes from `GET /api/v1/logs/config/indexes` (needs `logs_read_config`).
   */
  async listIndexes(options: QueryCallOptions = {}): Promise<DatadogLogIndex[]> {
    const op = "logs.listIndexes";
    const res = await datadogRequest<unknown>(this.#transport, {
      method: "GET",
      path: joinUrl(this.#config.apiUrl, INDEXES_PATH),
      headers: this.#headers(),
      operation: op,
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });
    const body = res.data;
    if (!isObject(body) || !Array.isArray(body.indexes)) {
      throw new UnavailableError(`${op}: unexpected response (no indexes array)`, {
        retryable: false,
        httpStatus: res.status,
      });
    }
    const out: DatadogLogIndex[] = [];
    for (const i of body.indexes) {
      if (!isObject(i) || typeof i.name !== "string") continue;
      const index: DatadogLogIndex = { name: i.name };
      if (isObject(i.filter) && typeof i.filter.query === "string") index.query = i.filter.query;
      if (typeof i.num_retention_days === "number") index.retentionDays = i.num_retention_days;
      if (typeof i.daily_limit === "number") index.dailyLimit = i.daily_limit;
      if (typeof i.is_rate_limited === "boolean") index.isRateLimited = i.is_rate_limited;
      out.push(index);
    }
    return out;
  }

  /** Datadog has no public API that lists facets or attributes. Always throws `UnsupportedError`. */
  override listLabels(): Promise<string[]> {
    return Promise.reject(
      new UnsupportedError("logging.listLabels: Datadog has no API that lists log attributes"),
    );
  }

  /**
   * Polling tail over {@link search} (Datadog Live Tail has no public API). Default interval 5000 ms
   * because the search API is rate limited.
   */
  override tail(
    query: string,
    onRecord: (record: LogRecord) => void,
    opts: TailOptions = {},
  ): TailHandle {
    return super.tail(query, onRecord, {
      ...opts,
      intervalMs: opts.intervalMs ?? DEFAULT_POLL_INTERVAL_MS,
    });
  }

  /**
   * Polls {@link search} until a record matches (default interval 5000 ms, timeout 30000 ms).
   * Authentication and validation errors propagate at once; `TimeoutError` at the deadline.
   */
  override waitForLog(query: string, opts: WaitForLogOptions = {}): Promise<LogRecord> {
    return super.waitForLog(query, {
      ...opts,
      intervalMs: opts.intervalMs ?? DEFAULT_POLL_INTERVAL_MS,
    });
  }
}

/** One Datadog log index. */
export interface DatadogLogIndex {
  name: string;
  /** Index filter query. */
  query?: string;
  retentionDays?: number;
  dailyLimit?: number;
  isRateLimited?: boolean;
}
