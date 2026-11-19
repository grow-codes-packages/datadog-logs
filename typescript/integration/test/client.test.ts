// Integration tests against datadog/fakeintake from docker/compose.yml. Every public function of
// @grow-codes/datadog-logs is called at least once:
//   - ingest (logClient, exporter, Logger) and key validation: real round trips — what the SDK sent
//     is read back from fakeintake's raw capture and asserted field by field;
//   - query API (searchLogs, iterateLogs, aggregateLogs, search, waitForLog, tail, listStreams,
//     listIndexes): fakeintake cannot search, so these are "wire stubs" — a canned official-shape
//     response is installed through an override, and the request the SDK sent is read back from the
//     capture. The real round trip runs in the live Datadog sandbox job (owner keys pending).

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { after, afterEach, before, describe, test } from "node:test";
import {
  AuthenticationError,
  ConfigError,
  DatadogLogsClient,
  DatadogLogsConfig,
  DatadogLogsError,
  MAX_LOG_BYTES,
  MAX_PAYLOAD_BYTES,
  Severity,
  UnsupportedError,
  ValidationError,
  traceIdToDatadog,
  spanIdToDatadog,
  type LogRecord,
  type DatadogLogEvent,
} from "@grow-codes/datadog-logs";
import { loadBootstrapEnv, runId } from "./env.ts";
import {
  entriesWith,
  fixtureBody,
  flushPayloads,
  intakePayloadsWith,
  override,
  payloads,
  restoreDefaults,
} from "./fakeintake.ts";

const run = `${runId}-${randomBytes(4).toString("hex")}`;
const clients: DatadogLogsClient[] = [];
const TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
const SPAN = "00f067aa0ba902b7";
const SEARCH = "/api/v2/logs/events/search";
const AGGREGATE = "/api/v2/logs/analytics/aggregate";
const INDEXES = "/api/v1/logs/config/indexes";

function client(
  overrides: ConstructorParameters<typeof DatadogLogsClient>[0] = {},
): DatadogLogsClient {
  const c = DatadogLogsClient.fromEnv({
    retry: { maxRetries: 1, initialBackoffMs: 10 },
    ...(overrides as object),
  });
  clients.push(c);
  return c;
}

function rec(body: string, extra: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: new Date(),
    severity: Severity.Info,
    severityText: "INFO",
    body,
    attributes: { run },
    resource: {},
    ...extra,
  };
}

before(() => {
  loadBootstrapEnv();
  assert.ok(process.env.GROW_DATADOG_LOGS_URL, "GROW_DATADOG_LOGS_URL must be set");
  assert.ok(process.env.DD_API_KEY, "DD_API_KEY must be set (bootstrap)");
});

afterEach(async () => {
  await restoreDefaults();
});

after(async () => {
  await Promise.all(clients.map((c) => c.close()));
});

describe("config + key validation", () => {
  test("fromEnv reads the bootstrap env (DD_API_KEY, DD_APP_KEY, GROW_DATADOG_LOGS_URL)", () => {
    const cfg = DatadogLogsConfig.fromEnv();
    assert.equal(cfg.intakeUrl, process.env.GROW_DATADOG_LOGS_INTAKE_URL);
    assert.equal(cfg.apiUrl, process.env.GROW_DATADOG_LOGS_API_URL);
    assert.equal(cfg.apiKey?.reveal(), process.env.DD_API_KEY);
    assert.ok(cfg.appKey !== undefined);
    assert.ok(!String(cfg).includes(process.env.DD_API_KEY ?? "--"));
  });

  test("health, ping and validateApiKey succeed (validate stub)", async () => {
    const c = client();
    const health = await c.health();
    assert.deepEqual(health, { healthy: true, status: "ok", details: { apiKeyValid: "true" } });
    const latency = await c.ping();
    assert.equal(typeof latency, "number");
    assert.ok(latency >= 0);
    assert.equal(await c.validateApiKey(), true);
  });

  test("403 from /api/v1/validate: validateApiKey false, ping AuthenticationError, health unauthorized", async () => {
    await override("GET", "/api/v1/validate", 403, { errors: ["Forbidden"] });
    const wrong = `wrong-key-${run}`;
    const c = client({ apiKey: wrong });
    assert.equal(await c.validateApiKey(), false);
    await assert.rejects(c.ping(), (err: unknown) => {
      assert.ok(err instanceof AuthenticationError);
      assert.equal(err.code, "AUTHENTICATION");
      assert.equal(err.httpStatus, 403);
      assert.ok(!err.message.includes(wrong));
      return true;
    });
    assert.deepEqual(await c.health(), {
      healthy: false,
      status: "unauthorized",
      details: { httpStatus: "403", apiKeyValid: "false" },
    });
  });

  test("serverVersion is unsupported; close is idempotent", async () => {
    const c = client();
    await assert.rejects(c.serverVersion(), UnsupportedError);
    await c.close();
    await c.close();
  });
});

describe("ingest round trips (fakeintake raw capture)", () => {
  test("logClient: send, sendBatch, logger/child, flush, stats, close → mapped Datadog entries", async () => {
    const marker = `rt-${run}`;
    const c = client({
      service: "checkout",
      env: "test",
      version: "1.2.3",
      hostname: "host-a",
      tags: "team:sdk",
    });
    const logs = c.logClient({ batch: { flushIntervalMs: 60_000 } });
    const t0 = Date.now();
    logs.send({
      message: `hello ${marker}`,
      severity: Severity.Warn,
      timestamp: new Date(t0),
      traceId: TRACE,
      spanId: SPAN,
      attributes: {
        marker,
        user: { id: 42, roles: ["admin"] },
        service: "user-svc",
        password: `hunter2-${run}`,
        unicode: "Grüße – 日本語  ",
      },
    });
    logs.sendBatch([
      { message: `batch-a ${marker}`, severity: Severity.Debug, attributes: { marker } },
      { message: `batch-b ${marker}`, severity: Severity.Fatal, attributes: { marker } },
    ]);
    logs.logger("it", { marker }).child({ component: "child" }).error(`logger ${marker}`, { n: 1 });
    await logs.flush();
    assert.deepEqual(logs.stats(), { queued: 0, sent: 4, dropped: 0, failed: 0, retries: 0 });
    await logs.close();

    const captured = await intakePayloadsWith(marker);
    assert.equal(captured.length, 1, "one gzip request for the whole batch");
    assert.equal(captured[0]?.encoding, "gzip");
    assert.equal(captured[0]?.apiKey, process.env.DD_API_KEY);
    const entries = await entriesWith(marker);
    assert.deepEqual(
      entries.map((e) => [e.message, e.status]),
      [
        [`hello ${marker}`, "warn"],
        [`batch-a ${marker}`, "debug"],
        [`batch-b ${marker}`, "fatal"],
        [`logger ${marker}`, "error"],
      ],
    );
    const first = entries[0] as Record<string, unknown>;
    assert.equal(first.timestamp, t0);
    assert.equal(first.service, "checkout");
    assert.equal(first.hostname, "host-a");
    assert.equal(first.ddsource, "grow-sdk");
    assert.equal(first.ddtags, "env:test,version:1.2.3,team:sdk");
    assert.equal(first["dd.trace_id"], traceIdToDatadog(TRACE));
    assert.equal(first["dd.trace_id"], "11803532876627986230");
    assert.equal(first["dd.span_id"], spanIdToDatadog(SPAN));
    assert.equal(first["otel.trace_id"], TRACE);
    assert.equal(first["otel.span_id"], SPAN);
    assert.deepEqual(first.user, { id: 42, roles: ["admin"] });
    assert.equal(first["attr.service"], "user-svc");
    assert.equal(first.password, "[REDACTED]");
    assert.equal(first.unicode, "Grüße – 日本語  ");
    assert.ok(!JSON.stringify(entries).includes(`hunter2-${run}`));
    assert.equal((entries[3] as Record<string, unknown>).component, "child");
  });

  test("exporter.export: plain JSON and gzip requests round-trip byte-exact", async () => {
    const marker = `exp-${run}`;
    const c = client();
    const plain = await c.exporter.export([rec(`plain ${marker}`)], { gzip: false });
    assert.deepEqual(plain, { accepted: 1, rejected: 0 });
    const gz = await c.exporter.export([rec(`gzip ${marker}`), rec(`gzip2 ${marker}`)], {
      gzip: true,
    });
    assert.deepEqual(gz, { accepted: 2, rejected: 0 });
    const captured = await intakePayloadsWith(marker);
    assert.deepEqual(
      captured.map((p) => [p.encoding, (p.json as unknown[]).length]),
      [
        ["application/json", 1],
        ["gzip", 2],
      ],
    );
    const enc = c.exporter.encode([rec(`gzip ${marker}`)]);
    assert.equal(enc.payloads.length, 1);
    await c.exporter.sendPayload(enc.payloads[0]!.body, { gzip: false });
    const again = await intakePayloadsWith(marker);
    assert.equal(JSON.stringify(again.at(-1)?.json), enc.payloads[0]!.body);
  });

  test("limits: ≤ 1000 entries and ≤ 5 MB per request, records > 1 MB truncated (asserted on the capture)", async () => {
    const marker = `lim-${run}`;
    const c = client({ totalTimeoutMs: 120_000 });
    const many = Array.from({ length: 2500 }, (_, i) => rec(`m${i} ${marker}`));
    assert.deepEqual(await c.exporter.export(many, { gzip: true }), {
      accepted: 2500,
      rejected: 0,
    });
    let captured = await intakePayloadsWith(marker);
    assert.deepEqual(
      captured.map((p) => (p.json as unknown[]).length),
      [1000, 1000, 500],
    );

    const big = `${marker}-big`;
    const bigOnes = Array.from({ length: 12 }, (_, i) => rec(`${big} ${i} `.padEnd(900_000, "x")));
    assert.deepEqual(await c.exporter.export(bigOnes, { gzip: true }), {
      accepted: 12,
      rejected: 0,
    });
    captured = await intakePayloadsWith(big);
    assert.ok(captured.length >= 3, `expected a split, got ${captured.length}`);
    for (const p of captured) assert.ok(p.bytes <= MAX_PAYLOAD_BYTES, `payload ${p.bytes} bytes`);
    assert.equal(
      captured.reduce((n, p) => n + (p.json as unknown[]).length, 0),
      12,
    );

    const huge = `${marker}-huge`;
    const r = await c.exporter.export([rec(`${huge} `.padEnd(1_100_000, "y"))], { gzip: true });
    assert.deepEqual(r, { accepted: 1, rejected: 0 });
    const [entry] = await entriesWith(huge);
    assert.ok(entry);
    assert.equal(entry["grow.truncated"], true);
    assert.ok(Buffer.byteLength(JSON.stringify(entry)) <= MAX_LOG_BYTES);
    assert.ok(Buffer.byteLength(JSON.stringify(entry)) > MAX_LOG_BYTES - 100);
    await flushPayloads();
  });

  test("intake errors injected through overrides map to SDK errors; 5xx are retried", async () => {
    const cases: [number, string, number][] = [
      [400, "VALIDATION", 1],
      [401, "AUTHENTICATION", 1],
      [403, "AUTHENTICATION", 1],
      [413, "VALIDATION", 1],
      [500, "UNAVAILABLE", 2],
      [503, "UNAVAILABLE", 2],
    ];
    const key = process.env.DD_API_KEY ?? "";
    for (const [status, code, attempts] of cases) {
      const marker = `err${status}-${run}`;
      await override("POST", "/api/v2/logs", status, {
        errors: ["Forbidden", "API key is missing or invalid"],
      });
      const result = await client().exporter.export([rec(marker)], { gzip: true });
      assert.equal(result.accepted, 0, String(status));
      assert.ok(result.error instanceof DatadogLogsError);
      assert.equal(result.error.code, code, String(status));
      assert.equal(result.error.httpStatus, status);
      assert.ok(!result.error.message.includes(key));
      assert.equal((await intakePayloadsWith(marker)).length, attempts, `${status} attempts`);
    }
    await restoreDefaults();
    const c = client();
    const errors: unknown[] = [];
    await override("POST", "/api/v2/logs", 403, { errors: ["Forbidden"] });
    const logs = c.logClient({
      batch: { flushIntervalMs: 60_000, onError: (e) => errors.push(e) },
    });
    logs.send({ message: `denied-${run}` });
    await logs.flush();
    assert.deepEqual(logs.stats(), { queued: 0, sent: 0, dropped: 0, failed: 1, retries: 0 });
    assert.ok(errors[0] instanceof AuthenticationError);
    await logs.close();
  });
});

describe("query API (wire stubs: canned official-shape responses via fakeintake overrides)", () => {
  after(async () => {
    // put fakeintake's defaults back for the search/aggregate routes
    await override("POST", SEARCH, 200, { errors: [] });
    await override("POST", AGGREGATE, 200, { errors: [] });
  });

  test("searchLogs + search: request body reaches the service, response is normalized", async () => {
    const marker = `q-${run}`;
    await override("POST", SEARCH, 200, fixtureBody("searchLogs.page1"));
    const q = client().query();
    const page = await q.searchLogs({
      query: `service:checkout "${marker}"`,
      from: "now-1h",
      indexes: ["main"],
      limit: 2,
    });
    assert.equal(page.events.length, 2);
    assert.equal(page.records[0]?.traceId, TRACE);
    assert.ok(page.nextCursor);
    const sent = (await payloads(SEARCH)).map((p) => p.json as Record<string, unknown>);
    const mine = sent.find((b) => JSON.stringify(b).includes(marker));
    assert.deepEqual(mine, {
      filter: {
        query: `service:checkout "${marker}"`,
        from: "now-1h",
        to: "now",
        indexes: ["main"],
        storage_tier: "indexes",
      },
      sort: "-timestamp",
      page: { limit: 2 },
    });
    const result = await q.search(marker, { limit: 2, order: "asc", cursor: page.nextCursor });
    assert.equal(result.records.length, 2);
    assert.equal(result.records[0]?.resource["service.name"], "checkout");
    const second = (await payloads(SEARCH)).map((p) => p.json as Record<string, unknown>).at(-1);
    assert.deepEqual((second as { page: unknown }).page, { limit: 2, cursor: page.nextCursor });
  });

  test("iterateLogs, waitForLog and tail over the stub", async () => {
    await override("POST", SEARCH, 200, fixtureBody("searchLogs.page2_last"));
    const q = client().query();
    const events: DatadogLogEvent[] = [];
    for await (const e of q.iterateLogs({ query: "run-7f3a", limit: 10 })) events.push(e);
    assert.deepEqual(
      events.map((e) => e.message),
      ["user signed in run-7f3a #0"],
    );
    const found = await q.waitForLog("run-7f3a", { timeoutMs: 5000, intervalMs: 100 });
    assert.equal(found.body, "user signed in run-7f3a #0");
    const got: LogRecord[] = [];
    const handle = q.tail("run-7f3a", (r) => got.push(r), {
      start: new Date("2026-11-18T12:00:00Z"),
      intervalMs: 100,
    });
    const deadline = Date.now() + 5000;
    while (got.length === 0 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 50));
    handle.stop();
    assert.equal(got[0]?.body, "user signed in run-7f3a #0");
  });

  test("aggregateLogs over the stub", async () => {
    await override("POST", AGGREGATE, 200, fixtureBody("aggregateLogs.count_by_service"));
    const q = client().query();
    const r = await q.aggregateLogs({ aggregation: "count" }, [{ facet: "service", limit: 10 }], {
      query: `agg-${run}`,
    });
    assert.deepEqual(r.buckets, [{ by: { service: "checkout" }, computes: { c0: 3 } }]);
    const sent = (await payloads(AGGREGATE)).map((p) => p.json as Record<string, unknown>);
    const mine = sent.find((b) => JSON.stringify(b).includes(`agg-${run}`));
    assert.deepEqual((mine as { group_by: unknown }).group_by, [{ facet: "service", limit: 10 }]);
  });

  test("listStreams / listIndexes over the stub; listLabels unsupported; fakeintake default is a protocol error", async () => {
    await override("GET", INDEXES, 200, fixtureBody("listStreams.indexes"));
    const q = client().query();
    assert.deepEqual(await q.listStreams(), ["main"]);
    assert.equal((await q.listIndexes())[0]?.retentionDays, 15);
    await assert.rejects(q.listLabels(), UnsupportedError);
    await override("POST", SEARCH, 200, { errors: [] });
    await assert.rejects(
      q.searchLogs(),
      (e: unknown) => e instanceof DatadogLogsError && e.code === "UNAVAILABLE",
    );
    await override("POST", SEARCH, 403, { errors: ["Forbidden"] });
    await assert.rejects(q.searchLogs(), AuthenticationError);
    await override("POST", SEARCH, 400, { errors: ["Invalid query"] });
    await assert.rejects(q.searchLogs({ query: "(" }), ValidationError);
  });

  test("query() without an application key is a ConfigError", () => {
    const c = new DatadogLogsClient({ apiKey: "k", url: process.env.GROW_DATADOG_LOGS_URL });
    clients.push(c);
    assert.throws(() => c.query(), ConfigError);
  });
});
