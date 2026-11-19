import type { Dispatcher } from "undici";
import { describe, expect, it } from "vitest";
import { joinUrl, rateLimitInterceptor } from "../src/index.js";

type AnyHandler = Record<string, unknown>;

/** Runs the interceptor against a fake dispatch and returns the handler it passed on. */
function intercept(handler: AnyHandler): AnyHandler {
  let passed: AnyHandler | undefined;
  const dispatch = ((_opts: unknown, h: AnyHandler) => {
    passed = h;
    return true;
  }) as unknown as Dispatcher["dispatch"];
  rateLimitInterceptor(dispatch)(
    { path: "/", method: "GET" },
    handler as unknown as Dispatcher.DispatchHandler,
  );
  return passed!;
}

describe("rateLimitInterceptor", () => {
  it("legacy onHeaders: adds retry-after from x-ratelimit-reset on 429/503 only", () => {
    const calls: string[][] = [];
    const handler: AnyHandler = {
      state: 7,
      onHeaders(this: AnyHandler, _status: number, raw: Buffer[]) {
        calls.push(raw.map(String));
        return this.state === 7;
      },
      onData: () => true,
    };
    const h = intercept(handler);
    const onHeaders = h.onHeaders as (
      s: number,
      raw: Buffer[],
      resume: () => void,
      t: string,
    ) => boolean;
    const reset = [Buffer.from("X-RateLimit-Reset"), Buffer.from(" 12 ")];
    expect(onHeaders(429, reset, () => undefined, "")).toBe(true);
    expect(calls[0]).toEqual(["X-RateLimit-Reset", " 12 ", "retry-after", "12"]);
    onHeaders(200, reset, () => undefined, "");
    expect(calls[1]).toEqual(["X-RateLimit-Reset", " 12 "]);
    onHeaders(503, [Buffer.from("retry-after"), Buffer.from("1"), ...reset], () => undefined, "");
    expect(calls[2]).toEqual(["retry-after", "1", "X-RateLimit-Reset", " 12 "]);
    onHeaders(429, [Buffer.from("x-ratelimit-reset"), Buffer.from("soon")], () => undefined, "");
    expect(calls[3]).toEqual(["x-ratelimit-reset", "soon"]);
    onHeaders(429, [], () => undefined, "");
    expect(calls[4]).toEqual([]);
    expect((h.onData as () => boolean)()).toBe(true);
    expect(h.state).toBe(7);
  });

  it("new-style onResponseStart: adds retry-after to the header object", () => {
    const seen: Record<string, unknown>[] = [];
    const h = intercept({
      onResponseStart: (_c: unknown, _s: number, headers: Record<string, unknown>) => {
        seen.push(headers);
      },
    });
    const start = h.onResponseStart as (c: unknown, s: number, h: Record<string, unknown>) => void;
    start({}, 429, { "x-ratelimit-reset": ["5", "6"] });
    start({}, 503, { "x-ratelimit-reset": "x" });
    start({}, 429, { "retry-after": "1", "x-ratelimit-reset": "5" });
    start({}, 204, { "x-ratelimit-reset": "5" });
    expect(seen).toEqual([
      { "x-ratelimit-reset": ["5", "6"], "retry-after": "5" },
      { "x-ratelimit-reset": "x" },
      { "retry-after": "1", "x-ratelimit-reset": "5" },
      { "x-ratelimit-reset": "5" },
    ]);
    expect(h.onHeaders).toBeUndefined();
  });

  it("joinUrl trims trailing slashes", () => {
    expect(joinUrl("http://a:1//", "/api/v2/logs")).toBe("http://a:1/api/v2/logs");
  });
});
