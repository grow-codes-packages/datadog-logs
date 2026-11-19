// Datadog test helpers: fixture loading, a local fake Datadog API and intake payload decoding.

import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { afterEach } from "vitest";
import { DatadogLogsClient, type DatadogLogsConfigOptions } from "../../src/index.js";
import { type MockHandler, type MockResponse, type MockServer, type RecordedRequest, startServer } from "./server.js";

/** Recorded fixture (prep `fixtures/`). */
export interface Fixture {
  request: { method: string; path: string; body?: unknown };
  response: { status: number; headers?: Record<string, string>; body?: unknown };
}

/** Loads `test/fixtures/<name>.json`. */
export function fixture(name: string): Fixture {
  return JSON.parse(
    readFileSync(new URL(`../fixtures/${name}.json`, import.meta.url), "utf8"),
  ) as Fixture;
}

/** The fixture's response as a mock response (only content-type and x-ratelimit headers kept). */
export function fixtureResponse(name: string): MockResponse {
  const f = fixture(name);
  const headers: Record<string, string> = {};
  for (const [k, v] of Object.entries(f.response.headers ?? {})) {
    if (k === "content-type" || k.startsWith("x-ratelimit") || k === "retry-after") headers[k] = v;
  }
  const body = f.response.body;
  return {
    status: f.response.status,
    headers,
    ...(body === undefined || body === null ? {} : { body: body as object }),
  };
}

const servers: MockServer[] = [];
const clients: DatadogLogsClient[] = [];

afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

/** Starts a mock server closed after the test. */
export async function server(handler: MockHandler | MockResponse[]): Promise<MockServer> {
  const s = await startServer(handler);
  servers.push(s);
  return s;
}

export const API_KEY = "dd-api-key-0123456789abcdef";
export const APP_KEY = "dd-app-key-fedcba9876543210";

/** Fast retry settings for unit tests. */
export const fast = { retry: { maxRetries: 2, initialBackoffMs: 1, maxBackoffMs: 5 } };

/** A client pointed at `s` (both bases), closed after the test. */
export function clientFor(s: MockServer, options: DatadogLogsConfigOptions = {}): DatadogLogsClient {
  const c = new DatadogLogsClient({
    url: s.url,
    apiKey: API_KEY,
    appKey: APP_KEY,
    ...fast,
    ...options,
  });
  clients.push(c);
  return c;
}

/** Decodes an intake request body (gzip or plain) into the JSON array. */
export function decodeIntake(req: RecordedRequest): Record<string, unknown>[] {
  const raw = req.headers["content-encoding"] === "gzip" ? gunzipSync(req.raw) : req.raw;
  return JSON.parse(raw.toString("utf8")) as Record<string, unknown>[];
}

/** The parsed JSON body of a recorded request. */
export function jsonBody(req: RecordedRequest): Record<string, unknown> {
  return JSON.parse(req.body) as Record<string, unknown>;
}
