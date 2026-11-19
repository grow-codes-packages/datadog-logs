import { describe, expect, it } from "vitest";
import {
  MAX_LOG_BYTES,
  RESERVED_KEYS,
  Severity,
  TRUNCATED_ATTRIBUTE,
  buildDdtags,
  datadogIdToHex,
  datadogStatus,
  eventToLogRecord,
  parseLogEvent,
  serializeEntry,
  severityFromStatus,
  spanIdToDatadog,
  toDatadogEntry,
  traceIdToDatadog,
  utf8Length,
  type DatadogLogEntry,
  type LogRecord,
} from "../src/index.js";
import { fixture } from "./helpers/datadog.js";

const TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
const SPAN = "00f067aa0ba902b7";

function record(overrides: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: new Date(1_795_033_887_895),
    severity: Severity.Info,
    severityText: "INFO",
    body: "user signed in",
    attributes: {},
    resource: {},
    ...overrides,
  };
}

describe("trace ids (DECISIONS 6)", () => {
  it("encodes the lower 64 bits as decimal strings", () => {
    expect(traceIdToDatadog(TRACE)).toBe(BigInt("0xa3ce929d0e0e4736").toString());
    expect(traceIdToDatadog("1")).toBe("1");
    expect(traceIdToDatadog("ABCDEF")).toBe(String(0xabcdef));
    expect(spanIdToDatadog(SPAN)).toBe(BigInt(`0x${SPAN}`).toString());
    for (const bad of [undefined, "", "xyz", "0".repeat(33)])
      expect(traceIdToDatadog(bad)).toBeUndefined();
    for (const bad of [undefined, "", "g1", "0".repeat(17)])
      expect(spanIdToDatadog(bad)).toBeUndefined();
  });

  it("decodes Datadog ids (decimal or hex) back to padded hex", () => {
    expect(datadogIdToHex(traceIdToDatadog(TRACE), 32)).toBe(`0000000000000000${TRACE.slice(-16)}`);
    expect(datadogIdToHex(TRACE, 32)).toBe(TRACE);
    expect(datadogIdToHex("ABC", 16)).toBe("0000000000000abc");
    expect(datadogIdToHex(255, 16)).toBe("00000000000000ff");
    expect(datadogIdToHex("18446744073709551615", 16)).toBe("ffffffffffffffff");
    for (const bad of [
      0,
      -1,
      1.5,
      "0",
      "",
      "zz",
      "18446744073709551616x",
      "1".repeat(21),
      null,
      {},
    ]) {
      expect(datadogIdToHex(bad, 16), JSON.stringify(bad)).toBeUndefined();
    }
    // 20 decimal digits above 2^64-1 and not hex-sized for 16 → invalid
    expect(datadogIdToHex("99999999999999999999", 16)).toBeUndefined();
  });
});

describe("status and tags", () => {
  it("status is the lower-cased severity text, else the canonical name", () => {
    expect(datadogStatus({ severity: Severity.Warn, severityText: "WARN" })).toBe("warn");
    expect(datadogStatus({ severity: Severity.Error, severityText: " " })).toBe("error");
    // eslint-disable-next-line @typescript-eslint/no-unsafe-enum-assignment
    expect(datadogStatus({ severity: Severity.Warn + 1, severityText: "" })).toBe("warn");
  });

  it("maps Datadog statuses to OTel severities", () => {
    const cases: [string | undefined, Severity][] = [
      ["emerg", Severity.Fatal],
      ["Critical", Severity.Fatal],
      ["error", Severity.Error],
      ["warning", Severity.Warn],
      ["notice", Severity.Info],
      ["ok", Severity.Info],
      ["debug", Severity.Debug],
      ["trace", Severity.Trace],
      ["weird", Severity.Info],
      [undefined, Severity.Info],
    ];
    for (const [status, sev] of cases) expect(severityFromStatus(status), status).toBe(sev);
  });

  it("ddtags: env, version, then tags; de-duplicated, no spaces", () => {
    expect(buildDdtags("test", "1.2.3", ["team:sdk", "env:test", "a b"])).toBe(
      "env:test,version:1.2.3,team:sdk,a_b",
    );
    expect(buildDdtags(undefined, undefined, [])).toBeUndefined();
    expect(buildDdtags(undefined, undefined)).toBeUndefined();
    expect(buildDdtags(undefined, undefined, [""])).toBeUndefined();
  });
});

describe("toDatadogEntry", () => {
  it("maps every reserved attribute", () => {
    const entry = toDatadogEntry(
      record({
        severity: Severity.Error,
        severityText: "ERROR",
        traceId: TRACE,
        spanId: SPAN,
        attributes: { user: { id: 42 }, service: "user-service", "dd.trace_id": "x", ok: true },
        resource: {
          "service.name": "checkout",
          "host.name": "host-a",
          "deployment.environment": "test",
          "service.version": "1.2.3",
          "cloud.region": "eu",
        },
      }),
      { source: "grow-sdk", tags: ["team:sdk"], service: "ignored", hostname: "ignored" },
    );
    expect(entry).toEqual({
      message: "user signed in",
      timestamp: 1_795_033_887_895,
      status: "error",
      service: "checkout",
      hostname: "host-a",
      ddsource: "grow-sdk",
      ddtags: "env:test,version:1.2.3,team:sdk",
      "dd.trace_id": traceIdToDatadog(TRACE),
      "dd.span_id": spanIdToDatadog(SPAN),
      "otel.trace_id": TRACE,
      "otel.span_id": SPAN,
      user: { id: 42 },
      "attr.service": "user-service",
      "attr.dd.trace_id": "x",
      ok: true,
    });
    expect(Object.keys(entry).slice(0, 3)).toEqual(["message", "timestamp", "status"]);
    expect(RESERVED_KEYS).toContain(TRUNCATED_ATTRIBUTE);
  });

  it("falls back to config defaults and omits what is unknown", () => {
    const entry = toDatadogEntry(record(), {
      service: "svc",
      hostname: "h",
      env: "prod",
      version: "2",
    });
    expect(entry).toEqual({
      message: "user signed in",
      timestamp: 1_795_033_887_895,
      status: "info",
      service: "svc",
      hostname: "h",
      ddtags: "env:prod,version:2",
    });
    expect(toDatadogEntry(record())).toEqual({
      message: "user signed in",
      timestamp: 1_795_033_887_895,
      status: "info",
    });
    // invalid trace id → no correlation fields; span without trace dropped too
    expect(
      toDatadogEntry(record({ traceId: "nothex", spanId: SPAN }))["dd.span_id"],
    ).toBeUndefined();
    // trace without span
    const t = toDatadogEntry(record({ traceId: TRACE, spanId: "zz" }));
    expect(t["otel.trace_id"]).toBe(TRACE);
    expect(t["dd.span_id"]).toBeUndefined();
    expect(t["otel.span_id"]).toBeUndefined();
    // non-string resource values are stringified; blank ones ignored
    const r = toDatadogEntry(record({ resource: { "service.name": 7, "host.name": " " } }));
    expect(r.service).toBe("7");
    expect(r.hostname).toBeUndefined();
  });
});

describe("serializeEntry (DECISIONS 7)", () => {
  const entry = (message: string, extra: Record<string, string> = {}): DatadogLogEntry =>
    ({ message, timestamp: 1, status: "info", ...extra }) as DatadogLogEntry;

  it("returns small entries unchanged", () => {
    const out = serializeEntry(entry("hi"));
    expect(out).toEqual({
      json: '{"message":"hi","timestamp":1,"status":"info"}',
      bytes: 46,
      truncated: false,
    });
  });

  it("truncates the message so the entry fits and marks it", () => {
    const big = "é".repeat(700_000); // 1.4 MB in UTF-8
    const out = serializeEntry(entry(big));
    expect(out?.truncated).toBe(true);
    expect(out!.bytes).toBeLessThanOrEqual(MAX_LOG_BYTES);
    expect(out!.bytes).toBeGreaterThan(MAX_LOG_BYTES - 3);
    expect(utf8Length(out!.json)).toBe(out!.bytes);
    const parsed = JSON.parse(out!.json) as Record<string, unknown>;
    expect(parsed[TRUNCATED_ATTRIBUTE]).toBe(true);
    expect(big.startsWith(parsed.message as string)).toBe(true);
  });

  it("never splits a surrogate pair and respects escaping", () => {
    const out = serializeEntry(entry("😀".repeat(50)), 120);
    const msg = (JSON.parse(out!.json) as { message: string }).message;
    expect(msg.length % 2).toBe(0);
    expect(out!.bytes).toBeLessThanOrEqual(120);
    const quoted = serializeEntry(entry('"'.repeat(200)), 150);
    expect(quoted!.bytes).toBeLessThanOrEqual(150);
    expect(JSON.parse(quoted!.json)).toHaveProperty(TRUNCATED_ATTRIBUTE, true);
  });

  it("cuts before a lone high surrogate", () => {
    // budget lands between the two halves of the emoji
    for (let max = 70; max < 90; max++) {
      const out = serializeEntry(entry("a😀😀😀😀😀😀"), max);
      if (out === undefined) continue;
      const msg = (JSON.parse(out.json) as { message: string }).message;
      expect(
        msg.charCodeAt(msg.length - 1) >= 0xd800 && msg.charCodeAt(msg.length - 1) <= 0xdbff,
      ).toBe(false);
    }
  });

  it("returns undefined when metadata alone is too large", () => {
    expect(serializeEntry(entry("x", { big: "y".repeat(500) }), 100)).toBeUndefined();
  });
});

describe("search event normalization", () => {
  it("parses the official search fixture", () => {
    const body = fixture("searchLogs.page1").response.body as { data: unknown[] };
    const event = parseLogEvent(body.data[0]);
    expect(event).toMatchObject({
      id: "AQAAAZq0001xLKzOnn44ewAAAABBWnFfX1h3ZzZobm1mWXJFYTR0OA",
      host: "host-a",
      service: "checkout",
      status: "info",
      message: "user signed in run-7f3a #2",
    });
    expect(event?.timestamp?.toISOString()).toBe("2026-11-18T12:01:02.345Z");
    const rec = eventToLogRecord(event!);
    expect(rec).toMatchObject({
      severity: Severity.Info,
      severityText: "info",
      body: "user signed in run-7f3a #2",
      traceId: TRACE,
      spanId: SPAN,
      resource: {
        "service.name": "checkout",
        "host.name": "host-a",
        "deployment.environment": "test",
        "service.version": "1.2.3",
      },
    });
    expect(rec.attributes["datadog.log_id"]).toBe(event?.id);
    expect(rec.attributes.run).toBe("run-7f3a");
    expect(rec.attributes["datadog.tags"]).toContain("env:test");
  });

  it("prefers otel ids, falls back to decimal dd ids, tolerates junk", () => {
    expect(parseLogEvent(null)).toBeUndefined();
    expect(parseLogEvent([])).toBeUndefined();
    const bare = parseLogEvent({ attributes: { timestamp: "nope", tags: [1, "a:b"] } })!;
    expect(bare).toEqual({ id: "", message: "", tags: ["a:b"], attributes: {} });
    const rec = eventToLogRecord(bare);
    expect(rec.timestamp.getTime()).toBe(0);
    expect(rec.severityText).toBe("info");
    expect(rec.traceId).toBeUndefined();
    expect(rec.attributes["datadog.log_id"]).toBeUndefined();
    expect(parseLogEvent({ id: 1, attributes: "x" })).toEqual({
      id: "",
      message: "",
      tags: [],
      attributes: {},
    });

    const otel = eventToLogRecord(
      parseLogEvent({
        id: "x",
        attributes: {
          status: "error",
          attributes: {
            "otel.trace_id": TRACE,
            otel: { span_id: SPAN },
            dd: { trace_id: traceIdToDatadog(TRACE), span_id: spanIdToDatadog(SPAN) },
          },
        },
      })!,
    );
    expect(otel.traceId).toBe(TRACE);
    expect(otel.spanId).toBe(SPAN);
    expect(otel.severity).toBe(Severity.Error);

    const dd = eventToLogRecord(
      parseLogEvent({
        attributes: { attributes: { dd: { trace_id: traceIdToDatadog(TRACE), span_id: "bad!" } } },
      })!,
    );
    expect(dd.traceId).toBe(`0000000000000000${TRACE.slice(-16)}`);
    expect(dd.spanId).toBeUndefined();
    const deep = eventToLogRecord(parseLogEvent({ attributes: { attributes: { otel: "flat" } } })!);
    expect(deep.traceId).toBeUndefined();
  });
});
