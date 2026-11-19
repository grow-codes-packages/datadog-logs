// Service-owned: Datadog-specific HTTP plumbing on top of the generated transport
// (DECISIONS 3: 403 → AuthenticationError; DECISIONS 5: X-RateLimit-Reset as retry hint).

import type { Dispatcher } from "undici";
import {
  AuthenticationError,
  DatadogLogsError,
  ErrorCode,
  type RequestOptions,
  type TransportResponse,
  type HttpTransport,
} from "./core/index.js";

/** Rate-limit headers sent by the Datadog API. */
export const RATE_LIMIT_RESET_HEADER = "x-ratelimit-reset";

function injectRetryAfterRaw(statusCode: number, raw: Buffer[] | string[]): Buffer[] | string[] {
  if (statusCode !== 429 && statusCode !== 503) return raw;
  let reset: string | undefined;
  for (let i = 0; i + 1 < raw.length; i += 2) {
    const name = String(raw[i]).toLowerCase();
    if (name === "retry-after") return raw;
    if (name === RATE_LIMIT_RESET_HEADER) reset = String(raw[i + 1]);
  }
  if (reset === undefined || !/^\d+(\.\d+)?$/.test(reset.trim())) return raw;
  return [...(raw as Buffer[]), Buffer.from("retry-after"), Buffer.from(reset.trim())];
}

function injectRetryAfterObject(
  statusCode: number,
  headers: Record<string, string | string[] | undefined>,
): Record<string, string | string[] | undefined> {
  if (statusCode !== 429 && statusCode !== 503) return headers;
  if (headers["retry-after"] !== undefined) return headers;
  const reset = headers[RATE_LIMIT_RESET_HEADER];
  const value = Array.isArray(reset) ? reset[0] : reset;
  if (value === undefined || !/^\d+(\.\d+)?$/.test(value.trim())) return headers;
  return { ...headers, "retry-after": value.trim() };
}

/**
 * undici interceptor: when a 429/503 response has no `Retry-After` but carries
 * `X-RateLimit-Reset` (seconds until the window resets), exposes it as `Retry-After` so the shared
 * transport honours it as the retry hint.
 */
export const rateLimitInterceptor: Dispatcher.DispatcherComposeInterceptor =
  (dispatch) => (opts, handler) => {
    const wrapped = new Proxy(handler, {
      get(target, prop): unknown {
        const h = target as unknown as Record<string | symbol, unknown>;
        if (prop === "onHeaders" && typeof h.onHeaders === "function") {
          const fn = h.onHeaders as (...a: unknown[]) => boolean;
          return (status: number, raw: Buffer[], ...rest: unknown[]) =>
            fn.call(target, status, injectRetryAfterRaw(status, raw), ...rest);
        }
        if (prop === "onResponseStart" && typeof h.onResponseStart === "function") {
          const fn = h.onResponseStart as (...a: unknown[]) => void;
          return (
            controller: unknown,
            status: number,
            headers: Record<string, string | string[] | undefined>,
            ...rest: unknown[]
          ): void => {
            fn.call(target, controller, status, injectRetryAfterObject(status, headers), ...rest);
          };
        }
        const value = h[prop];
        return typeof value === "function"
          ? (value as (...a: unknown[]) => unknown).bind(target)
          : value;
      },
    });
    return dispatch(opts, wrapped);
  };

/**
 * Maps Datadog's 403 (invalid or missing key, or a key without the needed permission) onto
 * {@link AuthenticationError}, keeping `httpStatus` 403, the request id and the message.
 */
export function mapDatadogError(err: unknown): unknown {
  if (!(err instanceof DatadogLogsError)) return err;
  if (err.code !== ErrorCode.PermissionDenied || err.httpStatus !== 403) return err;
  return new AuthenticationError(err.message, {
    httpStatus: 403,
    ...(err.requestId === undefined ? {} : { requestId: err.requestId }),
    ...(err.cause === undefined ? {} : { cause: err.cause }),
  });
}

/** Sends a request through the transport and applies {@link mapDatadogError}. */
export async function datadogRequest<T>(
  transport: HttpTransport,
  options: RequestOptions,
): Promise<TransportResponse<T>> {
  try {
    return await transport.request<T>(options);
  } catch (err) {
    throw mapDatadogError(err);
  }
}

/** Joins a base URL and an absolute path. */
export function joinUrl(base: string, path: string): string {
  return `${base.replace(/\/+$/, "")}${path}`;
}
