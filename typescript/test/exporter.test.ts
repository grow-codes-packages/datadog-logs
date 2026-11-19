import { describe, expect, it } from "vitest";
import {
  AuthenticationError,
  DatadogLogExporter,
  DatadogLogsClient,
  DatadogLogsConfig,
  HttpTransport,
  MAX_ENTRIES_PER_REQUEST,
  MAX_LOG_BYTES,
  MAX_PAYLOAD_BYTES,
  RateLimitError,
  Severity,
  TRUNCATED_ATTRIBUTE,
  ValidationError,
  rateLimitInterceptor,
  mapDatadogError,
  UnavailableError,
  type LogRecord,
} from "../src/index.js";
import {
  API_KEY,
  APP_KEY,
  clientFor,
  decodeIntake,
  fixtureResponse,
  server,
} from "./helpers/datadog.js";
import { captureLogger } from "./helpers/logger.js";

function rec(body: string, extra: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: new Date(1_800_000_000_000),
    severity: Severity.Info,
    severityText: "INFO",
    body,
    attributes: {},
    resource: {},
    ...extra,
  };
}

const accepted = fixtureResponse("send.datadog_202");

describe("DatadogLogExporter", () => {
  it("posts a gzip JSON array with DD-API-KEY only and the reserved fields", async () => {
    const s = await server([accepted]);
    const client = clientFor(s, {
      service: "checkout",
      hostname: "host-a",
      env: "test",
      version: "1.2.3",
      tags: "team:sdk",
    });
    const result = await client.exporter.export(
      [
        rec("user signed in", {
          traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
          spanId: "00f067aa0ba902b7",
          attributes: { user: { id: 42 } },
        }),
      ],
      { gzip: true },
    );
    expect(result).toEqual({ accepted: 1, rejected: 0 });
    const req = s.requests[0]!;
    expect(req.method).toBe("POST");
    expect(req.url).toBe("/api/v2/logs");
    expect(req.headers["dd-api-key"]).toBe(API_KEY);
    expect(req.headers["dd-application-key"]).toBeUndefined();
    expect(req.headers["content-encoding"]).toBe("gzip");
    expect(req.headers["content-type"]).toBe("application/json");
    expect(decodeIntake(req)).toEqual([
      {
        message: "user signed in",
        timestamp: 1_800_000_000_000,
        status: "info",
        service: "checkout",
        hostname: "host-a",
        ddsource: "grow-sdk",
        ddtags: "env:test,version:1.2.3,team:sdk",
        "dd.trace_id": "11803532876627986230",
        "dd.span_id": "67667974448284343",
        "otel.trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
        "otel.span_id": "00f067aa0ba902b7",
        user: { id: 42 },
      },
    ]);
    expect(APP_KEY).toBeTruthy();
  });

  it("sends uncompressed JSON when gzip is off and accepts any 2xx", async () => {
    const s = await server([{ status: 200, body: { errors: [] } }]);
    const client = clientFor(s);
    expect(await client.exporter.export([rec("a"), rec("b")], { gzip: false })).toEqual({
      accepted: 2,
      rejected: 0,
    });
    expect(s.requests[0]?.headers["content-encoding"]).toBeUndefined();
    expect(JSON.parse(s.requests[0]!.body)).toHaveLength(2);
    await client.exporter.sendPayload("[]");
    expect(s.requests[1]?.headers["content-encoding"]).toBe("gzip");
    expect(await client.exporter.export([], { gzip: true })).toEqual({ accepted: 0, rejected: 0 });
    expect(s.requests).toHaveLength(2);
    await client.exporter.shutdown();
  });

  it("splits at 1000 entries and 5 MB, truncates > 1 MB records, drops metadata-only giants", async () => {
    const s = await server([accepted]);
    const client = clientFor(s);
    const ex = client.exporter;
    const many = Array.from({ length: 2500 }, (_, i) => rec(`m${i}`));
    const enc = ex.encode(many);
    expect(enc.payloads.map((p) => p.entries)).toEqual([1000, 1000, 500]);
    for (const p of enc.payloads) expect(Buffer.byteLength(p.body)).toBe(p.bytes);

    const big = Array.from({ length: 12 }, (_, i) => rec(`${i}`.padEnd(900_000, "x")));
    const bigEnc = ex.encode(big);
    expect(bigEnc.payloads.map((p) => p.entries)).toEqual([5, 5, 2]);
    for (const p of bigEnc.payloads) expect(p.bytes).toBeLessThanOrEqual(MAX_PAYLOAD_BYTES);

    const huge = rec("y".repeat(1_100_000));
    const giant = rec("z", { attributes: { blob: "q".repeat(1_100_000) } });
    const mixed = ex.encode([huge, giant, rec("small")]);
    expect(mixed.truncated).toBe(1);
    expect(mixed.oversized).toBe(1);
    const sent = JSON.parse(mixed.payloads[0]!.body) as Record<string, unknown>[];
    expect(sent).toHaveLength(2);
    expect(sent[0]?.[TRUNCATED_ATTRIBUTE]).toBe(true);
    expect(Buffer.byteLength(JSON.stringify(sent[0]))).toBeLessThanOrEqual(MAX_LOG_BYTES);

    const result = await ex.export([huge, giant, rec("small")], { gzip: true });
    expect(result.accepted).toBe(2);
    expect(result.rejected).toBe(1);
    expect(result.error).toBeInstanceOf(ValidationError);
    expect(MAX_ENTRIES_PER_REQUEST).toBe(1000);
  });

  it("custom limits are capped at the Datadog limits and validated", async () => {
    const s = await server([accepted]);
    const client = clientFor(s);
    const cfg = client.config;
    const t = client.transport;
    const small = new DatadogLogExporter(t, cfg, {
      maxEntriesPerRequest: 2,
      maxPayloadBytes: 150,
      maxLogBytes: 120,
    });
    const enc = small.encode([rec("a"), rec("b"), rec("c"), rec("d".repeat(100))]);
    expect(enc.payloads.map((p) => p.entries)).toEqual([1, 1, 1, 1]);
    expect(enc.truncated).toBe(1);
    const capped = new DatadogLogExporter(t, cfg, { maxEntriesPerRequest: 5000 });
    expect(capped.encode(Array.from({ length: 1001 }, () => rec("x"))).payloads).toHaveLength(2);
    for (const bad of [0, -1, 1.5]) {
      expect(() => new DatadogLogExporter(t, cfg, { maxLogBytes: bad })).toThrow(ValidationError);
    }
  });

  it("stops at the first failing request and reports partial acceptance", async () => {
    const s = await server([accepted, fixtureResponse("send.400_injected")]);
    const client = clientFor(s);
    const many = Array.from({ length: 1500 }, (_, i) => rec(`m${i}`));
    const result = await client.exporter.export(many, { gzip: false });
    expect(result.accepted).toBe(1000);
    expect(result.rejected).toBe(500);
    expect(result.error).toBeInstanceOf(ValidationError);
    expect(result.error?.httpStatus).toBe(400);
  });

  it("maps intake statuses (403 → AuthenticationError) and never retries 4xx", async () => {
    const cases: [string, string, boolean][] = [
      ["send.400_injected", "VALIDATION", false],
      ["send.401_injected", "AUTHENTICATION", false],
      ["send.403_invalid_key", "AUTHENTICATION", false],
      ["send.403_missing_key", "AUTHENTICATION", false],
      ["send.413_injected", "VALIDATION", false],
    ];
    for (const [name, code, retryable] of cases) {
      const s = await server([fixtureResponse(name)]);
      const client = clientFor(s, { apiKey: "secret-api-key-value" });
      const result = await client.exporter.export([rec("x")], { gzip: true });
      expect(result.accepted, name).toBe(0);
      expect(result.error?.code, name).toBe(code);
      expect(result.error?.retryable, name).toBe(retryable);
      expect(result.error?.message).not.toContain("secret-api-key-value");
      expect(s.requests, name).toHaveLength(1);
    }
    const s = await server([fixtureResponse("send.403_invalid_key")]);
    const r = await clientFor(s).exporter.export([rec("x")], { gzip: true });
    expect(r.error).toBeInstanceOf(AuthenticationError);
    expect(r.error?.message).toBe("logs.send: Forbidden (HTTP 403)");
  });

  it("retries 408, 429, 500, 502, 503, 504 on the intake POST (DECISIONS 4)", async () => {
    for (const status of [408, 429, 500, 502, 503, 504]) {
      const s = await server([{ status, body: { errors: ["boom"] } }, accepted]);
      const client = clientFor(s);
      expect(await client.exporter.export([rec("x")], { gzip: true }), String(status)).toEqual({
        accepted: 1,
        rejected: 0,
      });
      expect(s.requests).toHaveLength(2);
    }
    const s = await server([fixtureResponse("send.503_injected")]);
    const exhausted = await clientFor(s).exporter.export([rec("x")], { gzip: true });
    expect(exhausted.error).toBeInstanceOf(UnavailableError);
    expect(exhausted.error?.retryable).toBe(true);
    expect(s.requests).toHaveLength(3);
  });

  it("honours Retry-After and X-RateLimit-Reset as the retry hint (DECISIONS 5)", async () => {
    const sleeps: number[] = [];
    const s = await server([
      { status: 429, headers: { "retry-after": "2" }, body: { errors: ["slow down"] } },
      { status: 429, headers: { "x-ratelimit-reset": "3" }, body: { errors: ["slow down"] } },
      { status: 503, headers: { "x-ratelimit-reset": "1", "retry-after": "4" } },
      accepted,
    ]);
    const client = new DatadogLogsClient(
      { url: s.url, apiKey: API_KEY, retry: { maxRetries: 3, initialBackoffMs: 1 } },
      {
        sleep: (ms) => {
          sleeps.push(ms);
          return Promise.resolve();
        },
      },
    );
    expect(await client.exporter.export([rec("x")], { gzip: true })).toEqual({
      accepted: 1,
      rejected: 0,
    });
    expect(sleeps).toEqual([2000, 3000, 4000]);
    await client.close();

    const tooLong = await server([
      fixtureResponse("send.429_injected"),
      { status: 429, headers: { "x-ratelimit-reset": "1820" } },
    ]);
    const c2 = clientFor(tooLong);
    const r = await c2.exporter.export([rec("x")], { gzip: true });
    expect(r.error).toBeInstanceOf(RateLimitError);
    expect(r.error?.retryAfterMs).toBe(1_820_000);
    // invalid reset value is ignored (plain backoff)
    const junk = await server([
      { status: 429, headers: { "x-ratelimit-reset": "soon" } },
      accepted,
    ]);
    expect((await clientFor(junk).exporter.export([rec("x")], { gzip: true })).accepted).toBe(1);
  });

  it("network errors are retried and reported as UnavailableError", async () => {
    const s = await server([{ destroy: true }, accepted]);
    expect((await clientFor(s).exporter.export([rec("x")], { gzip: true })).accepted).toBe(1);
    const dead = await server([{ destroy: true }]);
    const r = await clientFor(dead).exporter.export([rec("x")], { gzip: true });
    expect(r.error?.code).toBe("UNAVAILABLE");
  });

  it("cancellation via the export signal", async () => {
    const s = await server([accepted]);
    const r = await clientFor(s).exporter.export([rec("x")], {
      gzip: true,
      signal: AbortSignal.abort(),
    });
    expect(r.error?.code).toBe("CANCELLED");
  });

  it("wraps non-SDK failures", async () => {
    const cfg = new DatadogLogsConfig({ apiKey: API_KEY, url: "http://127.0.0.1:9" });
    const transport = {
      request: () => Promise.reject(new Error("weird")),
    } as unknown as HttpTransport;
    const r = await new DatadogLogExporter(transport, cfg).export([rec("x")], { gzip: false });
    expect(r.error).toBeInstanceOf(UnavailableError);
    expect(mapDatadogError("plain")).toBe("plain");
    expect(typeof rateLimitInterceptor).toBe("function");
  });

  it("core.redaction.error-text + core.hooks: keys never reach hooks or the logger", async () => {
    const s = await server([fixtureResponse("send.403_invalid_key")]);
    const logger = captureLogger();
    const seen: string[] = [];
    const client = clientFor(s, {
      apiKey: "very-secret-api-key",
      appKey: "very-secret-app-key",
      logger,
      hooks: {
        onRequest: (r) => seen.push(JSON.stringify(r)),
        onResponse: (r) => seen.push(JSON.stringify(r)),
        onError: (r) => seen.push(JSON.stringify(r)),
      },
    });
    await client.exporter.export([rec("x")], { gzip: true });
    await client.validateApiKey().catch(() => undefined);
    const all = seen.join("\n") + logger.text();
    expect(seen.length).toBeGreaterThan(2);
    expect(all).not.toContain("very-secret-api-key");
    expect(all).not.toContain("very-secret-app-key");
  });
});

describe("DatadogLogClient (batching)", () => {
  it("send/sendBatch/logger → one intake request with mapped records", async () => {
    const s = await server([accepted]);
    const client = clientFor(s, { service: "svc", env: "test", tags: ["team:sdk"] });
    const logs = client.logClient({ batch: { flushIntervalMs: 60_000 } });
    logs.send({ message: "one", attributes: { password: "hunter2", n: 1 } });
    logs.sendBatch([{ message: "two", severity: Severity.Warn }]);
    logs.logger("app", { component: "c" }).child({ req: "r1" }).error("three");
    await logs.flush();
    expect(logs.stats()).toMatchObject({ sent: 3, dropped: 0, failed: 0 });
    const sent = decodeIntake(s.requests[0]!);
    expect(sent.map((e) => [e.message, e.status, e.service, e.ddtags])).toEqual([
      ["one", "info", "svc", "env:test,team:sdk"],
      ["two", "warn", "svc", "env:test,team:sdk"],
      ["three", "error", "svc", "env:test,team:sdk"],
    ]);
    expect(sent[0]?.password).toBe("[REDACTED]");
    expect(sent[2]).toMatchObject({ component: "c", req: "r1" });
    await logs.close();
  });

  it("failed exports are counted and reported through onError", async () => {
    const s = await server([fixtureResponse("send.403_invalid_key")]);
    const errors: unknown[] = [];
    const logs = clientFor(s).logClient({
      batch: { flushIntervalMs: 60_000, onError: (e) => errors.push(e) },
    });
    logs.send({ message: "x" });
    await logs.flush();
    expect(logs.stats()).toMatchObject({ sent: 0, failed: 1 });
    expect(errors[0]).toBeInstanceOf(AuthenticationError);
  });
});
