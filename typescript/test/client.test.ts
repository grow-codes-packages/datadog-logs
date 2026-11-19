import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import {
  AuthenticationError,
  CancelledError,
  ConfigError,
  DatadogLogClient,
  DatadogLogsClient,
  DatadogLogsConfig,
  DatadogLogsError,
  DatadogLogsQuery,
  PACKAGE_KEY,
  REDACTED,
  RetryPolicy,
  Secret,
  VENDOR_ENV,
  parseTags,
  resolveSite,
  isDatadogSite,
  DATADOG_SITES,
} from "../src/index.js";
import {
  API_KEY,
  APP_KEY,
  clientFor,
  decodeIntake,
  fast,
  fixtureResponse,
  server,
} from "./helpers/datadog.js";
import { closedPort } from "./helpers/server.js";

const base = { apiKey: API_KEY };

describe("sites", () => {
  it("resolves every site to its intake and API hosts", () => {
    expect(DATADOG_SITES).toHaveLength(9);
    expect(resolveSite()).toEqual({
      site: "datadoghq.com",
      intakeUrl: "https://http-intake.logs.datadoghq.com",
      apiUrl: "https://api.datadoghq.com",
    });
    expect(resolveSite(" DatadogHQ.EU ").apiUrl).toBe("https://api.datadoghq.eu");
    expect(resolveSite("us2.ddog-gov.com").intakeUrl).toBe(
      "https://http-intake.logs.us2.ddog-gov.com",
    );
    expect(isDatadogSite("us3.datadoghq.com")).toBe(true);
    expect(isDatadogSite("app.datadoghq.com")).toBe(false);
    expect(() => resolveSite("us3")).toThrow(ConfigError);
  });
});

describe("DatadogLogsConfig", () => {
  it("uses defaults derived from the site and freezes", () => {
    const c = new DatadogLogsConfig(base);
    expect(c.site).toBe("datadoghq.com");
    expect(c.intakeUrl).toBe("https://http-intake.logs.datadoghq.com");
    expect(c.apiUrl).toBe("https://api.datadoghq.com");
    expect(c.url).toBe(c.apiUrl);
    expect(c.source).toBe("grow-sdk");
    expect(c.tags).toEqual([]);
    expect(c.requestTimeoutMs).toBe(30_000);
    expect(c.retry).toBeInstanceOf(RetryPolicy);
    expect(c.appKey).toBeUndefined();
    expect(Object.isFrozen(c)).toBe(true);
    expect(DatadogLogsConfig.from(c)).toBe(c);
    expect(DatadogLogsConfig.from(base).apiUrl).toBe(c.apiUrl);
    const prefix = `GROW_${PACKAGE_KEY.toUpperCase().replaceAll("-", "_")}_`;
    expect(DatadogLogsConfig.ENV_PREFIX).toBe(prefix);
    expect(DatadogLogsConfig.DEFAULT_SITE).toBe("datadoghq.com");
  });

  it("keeps optional settings; url sets both bases; explicit bases win", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const c = new DatadogLogsConfig({
      apiKey: new Secret("abcd"),
      appKey: "efgh",
      site: "datadoghq.eu",
      url: "http://fake:80",
      apiUrl: "http://api:81",
      service: "checkout",
      env: "test",
      version: "1.2.3",
      tags: "team:sdk, a:b c:d",
      hostname: "host-a",
      source: "custom",
      proxy: "http://p:1",
      userAgentSuffix: "s",
      hooks,
      logger,
    });
    expect(c.url).toBe("http://fake:80");
    expect(c.intakeUrl).toBe("http://fake:80");
    expect(c.apiUrl).toBe("http://api:81");
    expect(c.site).toBe("datadoghq.eu");
    expect(c.tags).toEqual(["team:sdk", "a:b", "c:d"]);
    expect([c.service, c.env, c.version, c.hostname, c.source]).toEqual([
      "checkout",
      "test",
      "1.2.3",
      "host-a",
      "custom",
    ]);
    expect(c.proxy).toBe("http://p:1");
    expect(c.userAgentSuffix).toBe("s");
    expect(c.hooks).toBe(hooks);
    expect(c.logger).toBe(logger);
    expect(c.knownSecrets()).toEqual(["abcd", "efgh"]);
    const blanks = new DatadogLogsConfig({ ...base, service: " ", source: "", url: " " });
    expect(blanks.service).toBeUndefined();
    expect(blanks.source).toBe("grow-sdk");
    expect(blanks.url).toBe(blanks.apiUrl);
  });

  it("validate() raises ConfigError", () => {
    const bad: Record<string, unknown>[] = [
      {},
      { apiKey: "   " },
      { ...base, appKey: " " },
      { ...base, site: "app.datadoghq.com" },
      { ...base, site: "nope", intakeUrl: "http://x" },
      { ...base, intakeUrl: "ftp://x" },
      { ...base, apiUrl: "notaurl" },
      { ...base, url: "ftp://x" },
    ];
    for (const options of bad) {
      expect(() => new DatadogLogsConfig(options), JSON.stringify(options)).toThrow(ConfigError);
    }
    // unknown site is fine when both bases are set
    expect(
      new DatadogLogsConfig({ ...base, site: "nope", intakeUrl: "http://i", apiUrl: "http://a" })
        .site,
    ).toBe("nope");
    expect(new DatadogLogsConfig({ ...base, site: "nope", url: "http://u" }).intakeUrl).toBe(
      "http://u",
    );
  });

  it("parseTags splits on commas and spaces and de-duplicates", () => {
    expect(parseTags(undefined)).toEqual([]);
    expect(parseTags("a:1,b:2 c:3  a:1,,")).toEqual(["a:1", "b:2", "c:3"]);
    expect(parseTags(["x:1", "y:2 z:3", "x:1"])).toEqual(["x:1", "y:2", "z:3"]);
  });

  it("core.redaction.config: toString/JSON/inspect hide credentials", () => {
    const c = new DatadogLogsConfig({
      apiKey: "super-secret-api",
      appKey: "super-secret-app",
      service: "svc",
      headers: { "x-api-key": "hdr-secret" },
    });
    for (const form of [String(c), JSON.stringify(c), inspect(c), `${c.apiKey}`, `${c.appKey}`]) {
      expect(form).not.toContain("super-secret-api");
      expect(form).not.toContain("super-secret-app");
      expect(form).not.toContain("hdr-secret");
    }
    expect(String(c)).toContain(REDACTED);
    expect(String(c)).toMatch(/^DatadogLogsConfig\(/);
    expect(c.toJSON()).toMatchObject({ service: "svc", site: "datadoghq.com", source: "grow-sdk" });
    expect(new DatadogLogsConfig(base).toJSON().appKey).toBeNull();
  });

  it("core.config.env-precedence: explicit > GROW_* > vendor > default", () => {
    const p = DatadogLogsConfig.ENV_PREFIX;
    const env = {
      [`${p}API_KEY`]: "grow-api",
      DD_API_KEY: "vendor-api",
      DD_APPLICATION_KEY: "vendor-app2",
      DD_SITE: "datadoghq.eu",
      DD_SERVICE: "svc-vendor",
      [`${p}SERVICE`]: "svc-grow",
      DD_ENV: "staging",
      DD_VERSION: "9.9.9",
      DD_TAGS: "team:a,tier:b",
      DD_HOSTNAME: "vendor-host",
      [`${p}SOURCE`]: "src",
      [`${p}INTAKE_URL`]: "http://intake:1",
      [`${p}REQUEST_TIMEOUT`]: "5s",
      [`${p}MAX_RETRIES`]: "7",
    };
    const c = DatadogLogsConfig.fromEnv({}, env);
    expect(c.apiKey?.reveal()).toBe("grow-api");
    expect(c.appKey?.reveal()).toBe("vendor-app2");
    expect(c.site).toBe("datadoghq.eu");
    expect(c.apiUrl).toBe("https://api.datadoghq.eu");
    expect(c.intakeUrl).toBe("http://intake:1");
    expect(c.service).toBe("svc-grow");
    expect(c.env).toBe("staging");
    expect(c.version).toBe("9.9.9");
    expect(c.tags).toEqual(["team:a", "tier:b"]);
    expect(c.hostname).toBe("vendor-host");
    expect(c.source).toBe("src");
    expect(c.requestTimeoutMs).toBe(5000);
    expect(c.retry.maxRetries).toBe(7);

    const vendor = DatadogLogsConfig.fromEnv(
      {},
      { DD_API_KEY: "v", DD_APP_KEY: "app1", DD_APPLICATION_KEY: "app2" },
    );
    expect(vendor.apiKey?.reveal()).toBe("v");
    expect(vendor.appKey?.reveal()).toBe("app1");
    expect(vendor.site).toBe("datadoghq.com");

    const grow = DatadogLogsConfig.fromEnv(
      {},
      { [`${p}API_KEY`]: "g", [`${p}URL`]: "http://u:1", [`${p}API_URL`]: "http://a:2" },
    );
    expect(grow.intakeUrl).toBe("http://u:1");
    expect(grow.apiUrl).toBe("http://a:2");

    const explicit = DatadogLogsConfig.fromEnv(
      { apiKey: "explicit", service: "svc-explicit", retry: { initialBackoffMs: 1 } },
      env,
    );
    expect(explicit.apiKey?.reveal()).toBe("explicit");
    expect(explicit.service).toBe("svc-explicit");
    expect(explicit.retry.maxRetries).toBe(7);
    expect(explicit.retry.initialBackoffMs).toBe(1);
    expect(VENDOR_ENV.apiKey).toEqual(["DD_API_KEY"]);

    let err: unknown;
    try {
      DatadogLogsConfig.fromEnv(
        {},
        { DD_API_KEY: "k", [`${p}TOTAL_TIMEOUT`]: "forever-and-secret" },
      );
    } catch (e) {
      err = e;
    }
    expect(err).toBeInstanceOf(ConfigError);
    expect((err as Error).message).toContain(`${p}TOTAL_TIMEOUT`);
    expect((err as Error).message).not.toContain("forever-and-secret");
    expect(() => DatadogLogsConfig.fromEnv({}, {})).toThrow(/apiKey is required/);
  });

  it("fromEnv reads process.env by default", () => {
    process.env.DD_API_KEY = "process-key";
    try {
      expect(DatadogLogsConfig.fromEnv().apiKey?.reveal()).toBe("process-key");
    } finally {
      delete process.env.DD_API_KEY;
    }
  });
});

describe("DatadogLogsClient core", () => {
  it("health, ping and validateApiKey use GET /api/v1/validate with DD-API-KEY only", async () => {
    const s = await server([fixtureResponse("validateApiKey.ok")]);
    const client = clientFor(s);
    expect(await client.health()).toEqual({
      healthy: true,
      status: "ok",
      details: { apiKeyValid: "true" },
    });
    expect(await client.ping()).toBeGreaterThanOrEqual(0);
    expect(await client.validateApiKey()).toBe(true);
    expect(s.requests).toHaveLength(3);
    for (const r of s.requests) {
      expect(r.method).toBe("GET");
      expect(r.url).toBe("/api/v1/validate");
      expect(r.headers["dd-api-key"]).toBe(API_KEY);
      expect(r.headers["dd-application-key"]).toBeUndefined();
      expect(r.headers["user-agent"]).toMatch(/^grow-codes-datadog-logs\/\S+ \(typescript\)$/);
    }
    expect(client.transport.userAgent).toContain(`grow-codes-${PACKAGE_KEY}/`);
  });

  it("validateApiKey returns false on 403 and {valid:false}; ping raises AuthenticationError", async () => {
    const s = await server([fixtureResponse("validateApiKey.403_invalid_key")]);
    const client = clientFor(s, { apiKey: "wrong-key-xyz" });
    expect(await client.validateApiKey()).toBe(false);
    const err = await client.ping().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AuthenticationError);
    expect((err as DatadogLogsError).httpStatus).toBe(403);
    expect((err as Error).message).toBe("ping: Forbidden (HTTP 403)");
    expect((err as Error).message).not.toContain("wrong-key-xyz");
    expect(await client.health()).toEqual({
      healthy: false,
      status: "unauthorized",
      details: { httpStatus: "403", apiKeyValid: "false" },
    });
    const f = await server([{ status: 200, body: { valid: false } }]);
    expect(await clientFor(f).validateApiKey()).toBe(false);
  });

  it("a 2xx without valid:true is a protocol error for ping and validateApiKey", async () => {
    const s = await server([{ status: 200 }]);
    const client = clientFor(s);
    await expect(client.ping()).rejects.toMatchObject({ code: "UNAVAILABLE", retryable: false });
    await expect(client.validateApiKey()).rejects.toMatchObject({ code: "UNAVAILABLE" });
    expect(await client.health()).toMatchObject({
      healthy: true,
      details: { apiKeyValid: "false" },
    });
  });

  it("health maps 429 → degraded, 5xx → unhealthy, closed port → unreachable", async () => {
    const limited = await server([{ status: 429, body: { errors: ["Too many requests"] } }]);
    expect(await clientFor(limited, { retry: { maxRetries: 0 } }).health()).toEqual({
      healthy: false,
      status: "degraded",
      details: { httpStatus: "429", error: "RATE_LIMITED" },
    });
    const down = await server([{ status: 503 }]);
    expect(await clientFor(down, { retry: { maxRetries: 0 } }).health()).toMatchObject({
      healthy: false,
      status: "unhealthy",
    });
    const port = await closedPort();
    const c = new DatadogLogsClient({ apiKey: API_KEY, url: `http://127.0.0.1:${port}`, ...fast });
    expect(await c.health()).toEqual({
      healthy: false,
      status: "unreachable",
      details: { error: "UNAVAILABLE" },
    });
    await c.close();
  });

  it("health rethrows cancellation; serverVersion is unsupported", async () => {
    const s = await server([fixtureResponse("validateApiKey.ok")]);
    const client = clientFor(s);
    await expect(client.health({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(client.ping({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(client.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
  });

  it("core.close.idempotent: close twice is safe and flushes log clients", async () => {
    const s = await server((req) =>
      req.url === "/api/v2/logs"
        ? { status: 202, body: {} }
        : { status: 200, body: { valid: true } },
    );
    const client = new DatadogLogsClient({ apiKey: API_KEY, url: s.url, ...fast });
    const logs = client.logClient({ batch: { flushIntervalMs: 60_000 } });
    logs.send({ message: "queued until close" });
    await client.close();
    await client.close();
    expect(decodeIntake(s.requests.find((r) => r.url === "/api/v2/logs")!)[0]?.message).toBe(
      "queued until close",
    );
    await expect(client.ping()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.health()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.exporter.sendPayload("[]")).rejects.toMatchObject({ code: "CANCELLED" });
    expect(() => client.logClient()).toThrow(CancelledError);
  });

  it("rejects unreadable TLS files with ConfigError", () => {
    expect(
      () =>
        new DatadogLogsClient({
          apiKey: API_KEY,
          tls: { caCertPath: "/nonexistent/ca.pem" },
        }),
    ).toThrow(ConfigError);
  });

  it("logClient seeds the resource from the config; query needs appKey", async () => {
    const s = await server([{ status: 202, body: {} }]);
    const client = clientFor(s, {
      service: "svc",
      version: "1.0.0",
      env: "test",
      hostname: "h1",
    });
    const logs = client.logClient({ resource: { "service.name": "override" } });
    expect(logs).toBeInstanceOf(DatadogLogClient);
    expect(logs.resource).toEqual({
      "service.name": "override",
      "service.version": "1.0.0",
      "deployment.environment": "test",
      "host.name": "h1",
    });
    expect(logs.exporter).toBe(client.exporter);
    const limited = client.logClient({ exporter: { maxEntriesPerRequest: 10 } });
    expect(limited.exporter).not.toBe(client.exporter);
    expect(client.query()).toBeInstanceOf(DatadogLogsQuery);
    expect(client.query()).toBe(client.query());
    const noApp = new DatadogLogsClient({ apiKey: API_KEY, url: s.url });
    expect(() => noApp.query()).toThrow(/appKey is required/);
    await noApp.close();
    expect(APP_KEY).not.toBe(API_KEY);
  });

  it("fromEnv builds from GROW_* and DD_* variables", async () => {
    const s = await server([fixtureResponse("validateApiKey.ok")]);
    const keys = { GROW_DATADOG_LOGS_URL: s.url, DD_API_KEY: API_KEY };
    Object.assign(process.env, keys);
    try {
      const client = DatadogLogsClient.fromEnv({ ...fast });
      expect(client.config.intakeUrl).toBe(s.url);
      expect(await client.ping()).toBeGreaterThanOrEqual(0);
      await client.close();
    } finally {
      for (const k of Object.keys(keys)) delete process.env[k];
    }
  });
});
