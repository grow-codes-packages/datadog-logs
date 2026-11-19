import { describe, expect, it } from "vitest";
import {
  AuthenticationError,
  DatadogLogsClient,
  RateLimitError,
  Severity,
  TimeoutError,
  UnavailableError,
  ValidationError,
  formatDatadogTime,
  type DatadogLogEvent,
  type LogRecord,
} from "../src/index.js";
import {
  API_KEY,
  APP_KEY,
  clientFor,
  fixture,
  fixtureResponse,
  jsonBody,
  server,
} from "./helpers/datadog.js";

const page1 = fixtureResponse("searchLogs.page1");
const page2 = fixtureResponse("searchLogs.page2_last");

describe("formatDatadogTime", () => {
  it("formats dates, epoch ms and strings", () => {
    expect(formatDatadogTime(new Date("2026-11-18T12:00:00Z"))).toBe("2026-11-18T12:00:00.000Z");
    expect(formatDatadogTime(0)).toBe("1970-01-01T00:00:00.000Z");
    expect(formatDatadogTime(" now-1h ")).toBe("now-1h");
    expect(() => formatDatadogTime(new Date("x"))).toThrow(ValidationError);
    expect(() => formatDatadogTime(Number.NaN)).toThrow(ValidationError);
    expect(() => formatDatadogTime(" ")).toThrow(ValidationError);
  });
});

describe("DatadogLogsQuery.searchLogs", () => {
  it("sends the LogsListRequest with both keys and parses the page", async () => {
    const s = await server([page1]);
    const q = clientFor(s).query();
    const page = await q.searchLogs({
      query: 'service:checkout "run-7f3a"',
      from: new Date("2026-11-18T12:00:00Z"),
      to: "2026-11-18T12:15:00Z",
      indexes: ["main"],
      limit: 2,
    });
    const req = s.requests[0]!;
    expect(req.method).toBe("POST");
    expect(req.url).toBe("/api/v2/logs/events/search");
    expect(req.headers["dd-api-key"]).toBe(API_KEY);
    expect(req.headers["dd-application-key"]).toBe(APP_KEY);
    expect(jsonBody(req)).toEqual({
      filter: {
        query: 'service:checkout "run-7f3a"',
        from: "2026-11-18T12:00:00.000Z",
        to: "2026-11-18T12:15:00Z",
        indexes: ["main"],
        storage_tier: "indexes",
      },
      sort: "-timestamp",
      page: { limit: 2 },
    });
    expect(page.events).toHaveLength(2);
    expect(page.records[0]?.body).toBe("user signed in run-7f3a #2");
    expect(page.records[0]?.severity).toBe(Severity.Info);
    expect(page.nextCursor).toMatch(/^eyJ/);
    expect(page.status).toBe("done");
    expect(page.requestId).toBe("cFJiNlp3...-redacted");
    expect(page.elapsedMs).toBe(38);
    expect(page.warnings).toEqual([]);
  });

  it("uses defaults and passes cursor, sort, storage tier", async () => {
    const s = await server([page2]);
    const q = clientFor(s).query();
    const page = await q.searchLogs({
      cursor: "abc",
      sort: "timestamp",
      storageTier: "flex",
      query: " ",
    });
    expect(jsonBody(s.requests[0]!)).toEqual({
      filter: { query: "*", from: "now-15m", to: "now", indexes: ["*"], storage_tier: "flex" },
      sort: "timestamp",
      page: { limit: 100, cursor: "abc" },
    });
    expect(page.nextCursor).toBeUndefined();
    await q.searchLogs();
    expect(jsonBody(s.requests[1]!).page).toEqual({ limit: 100 });
  });

  it("returns warnings and timeout status as partial results", async () => {
    const s = await server([
      fixtureResponse("searchLogs.warnings_partial"),
      fixtureResponse("searchLogs.timeout_status"),
    ]);
    const q = clientFor(s).query();
    const partial = await q.searchLogs({ indexes: ["main", "nope"] });
    expect(partial.warnings).toEqual([
      {
        code: "unknown_index",
        detail: "indexes: nope",
        title:
          "One or several indexes are missing or invalid, results hold data from the other indexes",
      },
    ]);
    const timeout = await q.searchLogs();
    expect(timeout.status).toBe("timeout");
    expect(timeout.events).toEqual([]);
  });

  it("validates input", async () => {
    const s = await server([page1]);
    const q = clientFor(s).query();
    for (const limit of [0, 5001, 1.5]) {
      await expect(q.searchLogs({ limit })).rejects.toBeInstanceOf(ValidationError);
    }
    await expect(q.searchLogs({ sort: "up" as "timestamp" })).rejects.toBeInstanceOf(
      ValidationError,
    );
    expect(s.requests).toHaveLength(0);
  });

  it("a 200 without a data array is a protocol error (fakeintake default)", async () => {
    const s = await server([
      fixtureResponse("searchLogs.fakeintake_default"),
      { status: 200 },
      {
        status: 200,
        body: {
          data: [null, { id: "x", attributes: { message: "m", status: "warn" } }],
          meta: { warnings: ["junk", {}] },
        },
      },
    ]);
    const q = clientFor(s).query();
    await expect(q.searchLogs()).rejects.toMatchObject({ code: "UNAVAILABLE", retryable: false });
    await expect(q.searchLogs()).rejects.toBeInstanceOf(UnavailableError);
    const ok = await q.searchLogs();
    expect(ok.events).toHaveLength(1);
    expect(ok.warnings).toEqual([{}]);
    expect(ok.records[0]?.severity).toBe(Severity.Warn);
  });

  it("maps errors: 400 validation, 401/403 authentication, 429 rate limit with reset hint, 500 retried", async () => {
    const cases: [string, string][] = [
      ["searchLogs.400_invalid_query", "VALIDATION"],
      ["searchLogs.401_missing_keys", "AUTHENTICATION"],
      ["searchLogs.403_invalid_keys", "AUTHENTICATION"],
    ];
    for (const [name, code] of cases) {
      const s = await server([fixtureResponse(name)]);
      const err = await clientFor(s)
        .query()
        .searchLogs()
        .catch((e: unknown) => e);
      expect((err as { code: string }).code, name).toBe(code);
    }
    const s403 = await server([fixtureResponse("searchLogs.403_invalid_keys")]);
    const e403 = await clientFor(s403)
      .query()
      .searchLogs()
      .catch((e: unknown) => e);
    expect(e403).toBeInstanceOf(AuthenticationError);
    expect((e403 as Error).message).toBe("logs.searchLogs: Forbidden (HTTP 403)");

    const limited = await server([fixtureResponse("searchLogs.429_rate_limited")]);
    const e429 = await clientFor(limited)
      .query()
      .searchLogs()
      .catch((e: unknown) => e);
    expect(e429).toBeInstanceOf(RateLimitError);
    expect((e429 as RateLimitError).retryAfterMs).toBe(1_820_000);
    expect(limited.requests).toHaveLength(1);

    const flaky = await server([{ status: 500, body: { errors: ["Internal"] } }, page2]);
    expect((await clientFor(flaky).query().searchLogs()).events).toHaveLength(1);
    expect(flaky.requests).toHaveLength(2);
  });
});

describe("DatadogLogsQuery.iterateLogs", () => {
  it("follows meta.page.after until the last page", async () => {
    const s = await server([page1, page2]);
    const q = clientFor(s).query();
    const events: DatadogLogEvent[] = [];
    for await (const e of q.iterateLogs({ query: "run-7f3a", pageSize: 2 })) events.push(e);
    expect(events.map((e) => e.message)).toEqual([
      "user signed in run-7f3a #2",
      "user signed in run-7f3a #1",
      "user signed in run-7f3a #0",
    ]);
    expect(jsonBody(s.requests[1]!).page).toEqual({
      limit: 2,
      cursor: (fixture("searchLogs.page1").response.body as { meta: { page: { after: string } } })
        .meta.page.after,
    });
  });

  it("caps the total with limit and stops on an empty page", async () => {
    const s = await server([page1]);
    const q = clientFor(s).query();
    const got: string[] = [];
    for await (const e of q.iterateLogs({ limit: 1 })) got.push(e.id);
    expect(got).toHaveLength(1);
    expect(jsonBody(s.requests[0]!).page).toEqual({ limit: 1 });
    const empty = await server([
      { status: 200, body: { data: [], meta: { page: { after: "c1" } } } },
    ]);
    const none: unknown[] = [];
    for await (const e of clientFor(empty).query().iterateLogs({ cursor: "c0" })) none.push(e);
    expect(none).toEqual([]);
    expect(jsonBody(empty.requests[0]!).page).toEqual({ limit: 1000, cursor: "c0" });
  });

  it("stops yielding when limit is reached inside a page", async () => {
    const s = await server([page1, page1]);
    const got: string[] = [];
    for await (const e of clientFor(s).query().iterateLogs({ limit: 3, pageSize: 2 }))
      got.push(e.message);
    expect(got).toHaveLength(3);
  });

  it("guards against a repeated cursor and validates arguments", async () => {
    const s = await server([page1]);
    const q = clientFor(s).query();
    const run = async (params: Parameters<typeof q.iterateLogs>[0]): Promise<unknown> => {
      try {
        for await (const e of q.iterateLogs(params)) expect(e).toBeDefined();
        return undefined;
      } catch (err) {
        return err;
      }
    };
    expect(await run({})).toMatchObject({
      code: "UNAVAILABLE",
      message: expect.stringMatching(/repeated a page cursor/) as unknown,
    });
    expect(await run({ limit: 0 })).toBeInstanceOf(ValidationError);
    expect(await run({ pageSize: 6000 })).toBeInstanceOf(ValidationError);
  });
});

describe("DatadogLogsQuery.aggregateLogs", () => {
  it("count grouped by service", async () => {
    const s = await server([fixtureResponse("aggregateLogs.count_by_service")]);
    const q = clientFor(s).query();
    const r = await q.aggregateLogs(
      { aggregation: "count" },
      [{ facet: "service", limit: 10, sort: { aggregation: "count", order: "desc" } }],
      {
        query: 'service:checkout "run-7f3a"',
        from: "2026-11-18T12:00:00Z",
        to: "2026-11-18T12:15:00Z",
        indexes: ["main"],
      },
    );
    const req = s.requests[0]!;
    expect(req.url).toBe("/api/v2/logs/analytics/aggregate");
    expect(req.headers["dd-application-key"]).toBe(APP_KEY);
    expect(jsonBody(req)).toEqual({
      compute: [{ aggregation: "count", type: "total" }],
      filter: {
        query: 'service:checkout "run-7f3a"',
        from: "2026-11-18T12:00:00Z",
        to: "2026-11-18T12:15:00Z",
        indexes: ["main"],
        storage_tier: "indexes",
      },
      group_by: [{ facet: "service", limit: 10, sort: { aggregation: "count", order: "desc" } }],
    });
    expect(r.buckets).toEqual([{ by: { service: "checkout" }, computes: { c0: 3 } }]);
    expect(r.status).toBe("done");
    expect(r.elapsedMs).toBe(52);
  });

  it("timeseries, paging cursor and multiple computes", async () => {
    const s = await server([
      fixtureResponse("aggregateLogs.timeseries"),
      fixtureResponse("aggregateLogs.paged"),
    ]);
    const q = clientFor(s).query();
    const ts = await q.aggregateLogs([
      { aggregation: "count", type: "timeseries", interval: "5m" },
      { aggregation: "avg", metric: "@duration" },
    ]);
    expect(jsonBody(s.requests[0]!).compute).toEqual([
      { aggregation: "count", type: "timeseries", interval: "5m" },
      { aggregation: "avg", type: "total", metric: "@duration" },
    ]);
    expect(jsonBody(s.requests[0]!).group_by).toBeUndefined();
    const points = ts.buckets[0]?.computes.c0 as { time: Date; value: number }[];
    expect(points).toHaveLength(3);
    expect(points[0]?.time.toISOString()).toBe("2026-11-18T12:00:00.000Z");
    expect(points[0]?.value).toBe(3);
    const paged = await q.aggregateLogs(
      { aggregation: "count" },
      [
        {
          facet: "@user.id",
          missing: "none",
          total: true,
          histogram: { interval: 1, min: 0, max: 10 },
        },
      ],
      { cursor: "cur" },
    );
    expect(jsonBody(s.requests[1]!).page).toEqual({ cursor: "cur" });
    expect(paged.nextCursor).toBe("eyJhZnRlciI6eyJAdXNlci5pZCI6WzQyXX19");
    expect(paged.buckets[0]?.by).toEqual({ "@user.id": 42 });
  });

  it("tolerates odd buckets and rejects bad input / bad responses", async () => {
    const s = await server([
      {
        status: 200,
        body: {
          data: {
            buckets: [
              null,
              {
                by: { a: true, b: "x" },
                computes: {
                  c0: "12",
                  c1: null,
                  c2: [null, { time: "t", value: "v" }, { time: "2026-01-01T00:00:00Z", value: 1 }],
                },
              },
              {},
            ],
          },
        },
      },
      { status: 200, body: { data: {} } },
      fixtureResponse("aggregateLogs.401_missing_keys"),
    ]);
    const q = clientFor(s).query();
    const r = await q.aggregateLogs({ aggregation: "count" });
    expect(r.buckets[0]).toEqual({
      by: { b: "x" },
      computes: { c0: "12", c2: [{ time: new Date("2026-01-01T00:00:00Z"), value: 1 }] },
    });
    expect(r.buckets[1]).toEqual({ by: {}, computes: {} });
    await expect(q.aggregateLogs({ aggregation: "count" })).rejects.toBeInstanceOf(
      UnavailableError,
    );
    await expect(q.aggregateLogs({ aggregation: "count" })).rejects.toBeInstanceOf(
      AuthenticationError,
    );
    await expect(q.aggregateLogs([])).rejects.toBeInstanceOf(ValidationError);
    await expect(q.aggregateLogs({} as { aggregation: "count" })).rejects.toBeInstanceOf(
      ValidationError,
    );
    await expect(
      q.aggregateLogs({ aggregation: "count" }, [{ facet: " " }]),
    ).rejects.toBeInstanceOf(ValidationError);
  });
});

describe("LogQueryClient mapping", () => {
  it("search maps start/end/order/cursor and returns normalized records", async () => {
    const s = await server([page1, fixtureResponse("searchLogs.warnings_partial")]);
    const q = clientFor(s).query();
    const r = await q.search("run-7f3a", {
      start: new Date("2026-11-18T12:00:00Z"),
      end: new Date("2026-11-18T12:15:00Z"),
      limit: 2,
      order: "asc",
      cursor: "cur",
    });
    expect(jsonBody(s.requests[0]!)).toMatchObject({
      filter: {
        query: "run-7f3a",
        from: "2026-11-18T12:00:00.000Z",
        to: "2026-11-18T12:15:00.000Z",
      },
      sort: "timestamp",
      page: { limit: 2, cursor: "cur" },
    });
    expect(r.records).toHaveLength(2);
    expect(r.nextCursor).toBeDefined();
    expect(r.status).toBe("done");
    expect(r.requestId).toBeDefined();
    expect(r.total).toBeUndefined();
    const second = await q.search("x");
    expect(jsonBody(s.requests[1]!)).toMatchObject({ sort: "-timestamp", page: { limit: 100 } });
    expect(second.nextCursor).toBeUndefined();
    expect(second.warnings).toHaveLength(1);
  });

  it("search without request id or status keeps them unset", async () => {
    const s = await server([{ status: 200, body: { data: [] } }]);
    const r = await clientFor(s).query().search("x", { signal: new AbortController().signal });
    expect(r).toEqual({ records: [], warnings: [] });
  });

  it("listStreams returns index names; listIndexes the details; listLabels is unsupported", async () => {
    const s = await server([
      fixtureResponse("listStreams.indexes"),
      fixtureResponse("listStreams.indexes"),
      { status: 200 },
      { status: 200, body: { indexes: [null, { name: "bare", filter: {} }] } },
    ]);
    const q = clientFor(s).query();
    expect(await q.listStreams()).toEqual(["main"]);
    expect(s.requests[0]?.method).toBe("GET");
    expect(s.requests[0]?.url).toBe("/api/v1/logs/config/indexes");
    expect(s.requests[0]?.headers["dd-application-key"]).toBe(APP_KEY);
    expect(await q.listIndexes({ signal: new AbortController().signal })).toEqual([
      {
        name: "main",
        query: "*",
        retentionDays: 15,
        dailyLimit: 300_000_000,
        isRateLimited: false,
      },
    ]);
    await expect(q.listIndexes()).rejects.toBeInstanceOf(UnavailableError);
    expect(await q.listIndexes()).toEqual([{ name: "bare" }]);
    await expect(q.listLabels()).rejects.toMatchObject({ code: "UNSUPPORTED" });
  });

  it("waitForLog polls until a record matches; errors propagate; times out", async () => {
    const s = await server([fixtureResponse("searchLogs.empty"), page1]);
    const q = clientFor(s).query();
    const found = await q.waitForLog("run-7f3a", {
      intervalMs: 5,
      timeoutMs: 5000,
      predicate: (r: LogRecord) => r.body.endsWith("#1"),
    });
    expect(found.body).toBe("user signed in run-7f3a #1");
    const denied = await server([fixtureResponse("searchLogs.403_invalid_keys")]);
    await expect(
      clientFor(denied).query().waitForLog("x", { intervalMs: 5 }),
    ).rejects.toBeInstanceOf(AuthenticationError);
    const empty = await server([fixtureResponse("searchLogs.empty")]);
    await expect(
      clientFor(empty).query().waitForLog("x", { intervalMs: 5, timeoutMs: 30 }),
    ).rejects.toBeInstanceOf(TimeoutError);
  });

  it("tail polls search and delivers new records once", async () => {
    const body = fixture("searchLogs.page1").response.body as { data: unknown[] };
    const asc = { status: 200, body: { data: [...body.data].reverse(), meta: { status: "done" } } };
    const s = await server([page2, asc, asc]);
    const q = clientFor(s).query();
    const got: LogRecord[] = [];
    const handle = q.tail("run-7f3a", (r) => got.push(r), {
      start: new Date("2026-11-18T12:00:00Z"),
      intervalMs: 5,
    });
    const deadline = Date.now() + 5000;
    while (got.length < 3 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 5));
    handle.stop();
    expect(got.map((r) => r.body).sort()).toEqual([
      "user signed in run-7f3a #0",
      "user signed in run-7f3a #1",
      "user signed in run-7f3a #2",
    ]);
    expect(jsonBody(s.requests[0]!)).toMatchObject({
      sort: "timestamp",
      filter: { from: "2026-11-18T12:00:00.000Z" },
    });
  });

  it("tail defaults to a 5 s interval", async () => {
    const s = await server([page2]);
    const handle = clientFor(s)
      .query()
      .tail("x", () => undefined);
    await new Promise((r) => setTimeout(r, 50));
    handle.stop();
    expect(s.requests).toHaveLength(0);
    const c = new DatadogLogsClient({ apiKey: API_KEY, appKey: APP_KEY, url: s.url });
    expect(c.query()).toBeDefined();
    await c.close();
  });
});
