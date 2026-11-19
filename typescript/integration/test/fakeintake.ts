// Helpers for the datadog/fakeintake test harness (API.md §7): read captured intake payloads back
// from the raw view, inject responses through overrides, and a fakeintake-backed LogQueryClient
// so the shared logging conformance scenarios can verify the real ingest path.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { gunzipSync, inflateSync } from "node:zlib";
import {
  Severity,
  UnsupportedError,
  type LogQueryClient,
  type LogRecord,
  type LogSearchOptions,
  type LogSearchResult,
  type TailHandle,
} from "@grow-codes/datadog-logs";

export const LOGS_ENDPOINT = "/api/v2/logs";

export function fakeintakeUrl(): string {
  const url = process.env.FAKEINTAKE_URL ?? process.env.GROW_DATADOG_LOGS_URL;
  assert.ok(url, "FAKEINTAKE_URL must be set (from /bootstrap/env)");
  return url.replace(/\/+$/, "");
}

/** One captured request of the raw view, decoded. */
export interface CapturedPayload {
  apiKey: string;
  encoding: string;
  /** Decoded (uncompressed) body bytes. */
  bytes: number;
  /** The JSON body (array for the intake). */
  json: unknown;
}

interface RawPayload {
  api_key?: string;
  data: string;
  encoding?: string;
}

/** Every payload captured for `endpoint`, decoded (base64 → gunzip/inflate → JSON). */
export async function payloads(endpoint = LOGS_ENDPOINT): Promise<CapturedPayload[]> {
  const res = await fetch(
    `${fakeintakeUrl()}/fakeintake/payloads?endpoint=${encodeURIComponent(endpoint)}`,
  );
  assert.equal(res.status, 200, `fakeintake payloads: HTTP ${res.status}`);
  const body = (await res.json()) as { payloads: RawPayload[] | null };
  return (body.payloads ?? []).map((p) => {
    const raw = Buffer.from(p.data, "base64");
    const encoding = p.encoding ?? "";
    const decoded =
      encoding === "gzip" ? gunzipSync(raw) : encoding === "deflate" ? inflateSync(raw) : raw;
    const text = decoded.toString("utf8");
    return {
      apiKey: p.api_key ?? "",
      encoding,
      bytes: decoded.length,
      json: text === "" ? null : (JSON.parse(text) as unknown),
    };
  });
}

/** Intake payloads (JSON arrays) that contain `marker` somewhere. */
export async function intakePayloadsWith(marker: string): Promise<CapturedPayload[]> {
  return (await payloads()).filter(
    (p) => Array.isArray(p.json) && JSON.stringify(p.json).includes(marker),
  );
}

/** Every captured intake entry whose JSON contains `marker`, in capture order. */
export async function entriesWith(marker: string): Promise<Record<string, unknown>[]> {
  const out: Record<string, unknown>[] = [];
  for (const p of await payloads()) {
    if (!Array.isArray(p.json)) continue;
    for (const entry of p.json as Record<string, unknown>[]) {
      if (JSON.stringify(entry).includes(marker)) out.push(entry);
    }
  }
  return out;
}

/** Installs a fixed response for `method endpoint` (payloads stay captured). */
export async function override(
  method: string,
  endpoint: string,
  status: number,
  body: unknown,
): Promise<void> {
  const res = await fetch(`${fakeintakeUrl()}/fakeintake/configure/override`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      method,
      endpoint,
      status_code: status,
      content_type: "application/json",
      body: Buffer.from(JSON.stringify(body)).toString("base64"),
    }),
  });
  assert.equal(res.status, 200, `override ${method} ${endpoint}: HTTP ${res.status}`);
}

/** Restores the bootstrap overrides (intake 202 {}, validate 200 {"valid":true}). */
export async function restoreDefaults(): Promise<void> {
  await override("POST", LOGS_ENDPOINT, 202, {});
  await override("GET", "/api/v1/validate", 200, { valid: true });
}

/** Clears every captured payload (overrides survive). The suite owns its container. */
export async function flushPayloads(): Promise<void> {
  const res = await fetch(`${fakeintakeUrl()}/fakeintake/flushPayloads`);
  assert.equal(res.status, 200);
}

/** Response body of a prep fixture copied to `test/fixtures/<name>.json`. */
export function fixtureBody(name: string): unknown {
  const f = JSON.parse(
    readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), "utf8"),
  ) as { response: { body: unknown } };
  return f.response.body;
}

const SEVERITY: Record<string, Severity> = {
  trace: Severity.Trace,
  debug: Severity.Debug,
  info: Severity.Info,
  warn: Severity.Warn,
  error: Severity.Error,
  fatal: Severity.Fatal,
};

const MAPPED = new Set([
  "message",
  "timestamp",
  "status",
  "service",
  "hostname",
  "ddsource",
  "ddtags",
  "dd.trace_id",
  "dd.span_id",
  "otel.trace_id",
  "otel.span_id",
]);

/** Turns a captured intake entry back into a LogRecord (test-side reverse mapping). */
export function entryToRecord(entry: Record<string, unknown>): LogRecord {
  const status = typeof entry.status === "string" ? entry.status : "info";
  const attributes: Record<string, never> = {};
  for (const [k, v] of Object.entries(entry)) {
    if (!MAPPED.has(k)) (attributes as Record<string, unknown>)[k] = v;
  }
  const record: LogRecord = {
    timestamp: new Date(typeof entry.timestamp === "number" ? entry.timestamp : 0),
    severity: SEVERITY[status] ?? Severity.Info,
    severityText: status,
    body: typeof entry.message === "string" ? entry.message : "",
    attributes,
    resource: typeof entry.service === "string" ? { "service.name": entry.service } : {},
  };
  if (typeof entry["otel.trace_id"] === "string") record.traceId = entry["otel.trace_id"];
  if (typeof entry["otel.span_id"] === "string") record.spanId = entry["otel.span_id"];
  return record;
}

/**
 * A LogQueryClient over fakeintake's capture: `search(text)` returns every captured entry whose
 * JSON contains `text` (offset cursor). It proves what reached the intake; it is not Datadog search.
 */
export class FakeintakeLogQuery implements LogQueryClient {
  async search(query: string, opts: LogSearchOptions = {}): Promise<LogSearchResult> {
    const all = (await entriesWith(query)).map(entryToRecord);
    const offset = opts.cursor === undefined ? 0 : Number(opts.cursor);
    const limit = opts.limit ?? 100;
    const records = all.slice(offset, offset + limit);
    const result: LogSearchResult = { records, total: all.length };
    if (offset + limit < all.length) result.nextCursor = String(offset + limit);
    return result;
  }

  tail(): TailHandle {
    throw new UnsupportedError("fakeintake: no tail");
  }

  listStreams(): Promise<string[]> {
    return Promise.reject(new UnsupportedError("fakeintake: no streams"));
  }

  listLabels(): Promise<string[]> {
    return Promise.reject(new UnsupportedError("fakeintake: no labels"));
  }

  async waitForLog(query: string): Promise<LogRecord> {
    const r = await this.search(query, { limit: 1 });
    assert.ok(r.records[0], `no record for ${query}`);
    return r.records[0];
  }
}
