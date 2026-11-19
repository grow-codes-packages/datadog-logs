// Service-owned: LogRecord ⇄ Datadog JSON (API.md §5.2, §6.1; DECISIONS 6, 7, 9).

import {
  Severity,
  severityText,
  toAttributes,
  type AttrValue,
  type Attributes,
  type LogRecord,
} from "./logging/index.js";

/** Maximum entries in one intake request (Datadog limit). */
export const MAX_ENTRIES_PER_REQUEST = 1000;
/** Maximum uncompressed intake payload in bytes, brackets and commas included (Datadog: 5 MB). */
export const MAX_PAYLOAD_BYTES = 5_000_000;
/** Maximum serialized size of one log entry in bytes (Datadog: 1 MB). */
export const MAX_LOG_BYTES = 1_000_000;
/** Attribute set to `true` on an entry whose message was truncated by the SDK. */
export const TRUNCATED_ATTRIBUTE = "grow.truncated";
/** Prefix given to user attributes whose name is reserved (DECISIONS 7). */
export const RESERVED_PREFIX = "attr.";

/**
 * Top-level keys the SDK writes itself. A user attribute with one of these names is sent as
 * `attr.<name>` instead.
 */
export const RESERVED_KEYS: readonly string[] = Object.freeze([
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
  TRUNCATED_ATTRIBUTE,
]);

/** One log entry as sent to `POST /api/v2/logs`. */
export type DatadogLogEntry = Record<string, AttrValue> & {
  message: string;
  timestamp: number;
  status: string;
};

/** Defaults applied when a record does not carry the value in its resource. */
export interface EntryDefaults {
  service?: string;
  hostname?: string;
  env?: string;
  version?: string;
  tags?: readonly string[];
  source?: string;
}

const HEX = /^[0-9a-f]+$/;
const DECIMAL = /^\d+$/;
const MAX_U64 = (1n << 64n) - 1n;

/**
 * Datadog trace-id form (DECISIONS 6): the decimal string of the lower 64 bits of an OpenTelemetry
 * trace id (32 hex). Returns `undefined` for invalid input.
 *
 * @example traceIdToDatadog("4bf92f3577b34da6a3ce929d0e0e4736") // "11803532876627986230"
 */
export function traceIdToDatadog(traceId: string | undefined): string | undefined {
  if (traceId === undefined) return undefined;
  const hex = traceId.trim().toLowerCase();
  if (hex === "" || hex.length > 32 || !HEX.test(hex)) return undefined;
  return BigInt(`0x${hex.slice(-16)}`).toString(10);
}

/** Datadog span-id form: the decimal string of a 16-hex OpenTelemetry span id. */
export function spanIdToDatadog(spanId: string | undefined): string | undefined {
  if (spanId === undefined) return undefined;
  const hex = spanId.trim().toLowerCase();
  if (hex === "" || hex.length > 16 || !HEX.test(hex)) return undefined;
  return BigInt(`0x${hex}`).toString(10);
}

/**
 * Converts a Datadog id (decimal string of up to 64 bits, or already lower hex) to lower hex padded
 * to `length` characters. Returns `undefined` for invalid or zero ids.
 */
export function datadogIdToHex(id: unknown, length: 16 | 32): string | undefined {
  let text: string;
  if (typeof id === "number" && Number.isSafeInteger(id) && id > 0) text = String(id);
  else if (typeof id === "string") text = id.trim().toLowerCase();
  else return undefined;
  let hex: string;
  if (DECIMAL.test(text) && text.length <= 20 && BigInt(text) <= MAX_U64) {
    hex = BigInt(text).toString(16);
  } else if (HEX.test(text) && text.length <= length) {
    hex = text;
  } else {
    return undefined;
  }
  const padded = hex.padStart(length, "0");
  return /^0+$/.test(padded) ? undefined : padded;
}

/** Datadog `status` of a record: its severity text lower-cased, else the canonical severity name. */
export function datadogStatus(record: Pick<LogRecord, "severity" | "severityText">): string {
  const text = record.severityText.trim().toLowerCase();
  return text !== "" ? text : severityText(record.severity).toLowerCase();
}

const SEVERITY_BY_STATUS: Readonly<Record<string, Severity>> = {
  emerg: Severity.Fatal,
  emergency: Severity.Fatal,
  alert: Severity.Fatal,
  crit: Severity.Fatal,
  critical: Severity.Fatal,
  fatal: Severity.Fatal,
  panic: Severity.Fatal,
  err: Severity.Error,
  error: Severity.Error,
  warn: Severity.Warn,
  warning: Severity.Warn,
  notice: Severity.Info,
  info: Severity.Info,
  information: Severity.Info,
  informational: Severity.Info,
  ok: Severity.Info,
  success: Severity.Info,
  debug: Severity.Debug,
  trace: Severity.Trace,
};

/** OpenTelemetry severity of a Datadog `status` (unknown values → `Info`). */
export function severityFromStatus(status: string | undefined): Severity {
  return SEVERITY_BY_STATUS[(status ?? "").trim().toLowerCase()] ?? Severity.Info;
}

function stringAttr(value: AttrValue | undefined): string | undefined {
  if (value === undefined) return undefined;
  const text = typeof value === "string" ? value : JSON.stringify(value);
  return text.trim() === "" ? undefined : text;
}

/**
 * Builds the `ddtags` value: `env:`, `version:`, then the configured tags; comma-joined,
 * de-duplicated, no spaces. Returns `undefined` when there is no tag.
 */
export function buildDdtags(
  env: string | undefined,
  version: string | undefined,
  tags: readonly string[] = [],
): string | undefined {
  const out: string[] = [];
  const add = (tag: string): void => {
    const clean = tag.replace(/[\s,]+/g, "_");
    if (clean !== "" && !out.includes(clean)) out.push(clean);
  };
  if (env !== undefined) add(`env:${env}`);
  if (version !== undefined) add(`version:${version}`);
  for (const tag of tags) add(tag);
  return out.length === 0 ? undefined : out.join(",");
}

/**
 * Converts a {@link LogRecord} into a Datadog intake entry (API.md §5.2):
 * `message`, `timestamp` (epoch ms), `status`, `service`, `hostname`, `ddsource`, `ddtags`,
 * `dd.trace_id` / `dd.span_id` (decimal, DECISIONS 6) plus `otel.trace_id` / `otel.span_id`
 * (lower hex, lossless), then the record attributes (other resource keys are not sent). User attributes
 * named like a reserved key are prefixed with `attr.`.
 */
export function toDatadogEntry(record: LogRecord, defaults: EntryDefaults = {}): DatadogLogEntry {
  const resource = record.resource;
  const entry: Record<string, AttrValue> = {
    message: record.body,
    timestamp: record.timestamp.getTime(),
    status: datadogStatus(record),
  };
  const service = stringAttr(resource["service.name"]) ?? defaults.service;
  if (service !== undefined) entry.service = service;
  const hostname = stringAttr(resource["host.name"]) ?? defaults.hostname;
  if (hostname !== undefined) entry.hostname = hostname;
  if (defaults.source !== undefined) entry.ddsource = defaults.source;
  const ddtags = buildDdtags(
    stringAttr(resource["deployment.environment"]) ?? defaults.env,
    stringAttr(resource["service.version"]) ?? defaults.version,
    defaults.tags,
  );
  if (ddtags !== undefined) entry.ddtags = ddtags;
  const ddTrace = traceIdToDatadog(record.traceId);
  if (ddTrace !== undefined && record.traceId !== undefined) {
    entry["dd.trace_id"] = ddTrace;
    const ddSpan = spanIdToDatadog(record.spanId);
    if (ddSpan !== undefined) entry["dd.span_id"] = ddSpan;
    entry["otel.trace_id"] = record.traceId.trim().toLowerCase().padStart(32, "0");
    if (ddSpan !== undefined && record.spanId !== undefined) {
      entry["otel.span_id"] = record.spanId.trim().toLowerCase().padStart(16, "0");
    }
  }
  for (const [key, value] of Object.entries(record.attributes)) {
    entry[RESERVED_KEYS.includes(key) ? `${RESERVED_PREFIX}${key}` : key] = value;
  }
  return entry as DatadogLogEntry;
}

/** UTF-8 byte length of a string. */
export function utf8Length(text: string): number {
  return Buffer.byteLength(text, "utf8");
}

/**
 * Serializes an entry so it is at most `maxBytes` (DECISIONS 7). An oversized entry gets its
 * `message` cut at a character boundary and `grow.truncated: true`. Returns `undefined` when even an
 * empty message does not fit (the metadata alone is too large).
 */
export function serializeEntry(
  entry: DatadogLogEntry,
  maxBytes: number = MAX_LOG_BYTES,
): { json: string; bytes: number; truncated: boolean } | undefined {
  const json = JSON.stringify(entry);
  const bytes = utf8Length(json);
  if (bytes <= maxBytes) return { json, bytes, truncated: false };
  const message = entry.message;
  const base = { ...entry, message: "", [TRUNCATED_ATTRIBUTE]: true };
  const overhead = utf8Length(JSON.stringify(base));
  if (overhead > maxBytes) return undefined;
  const budget = maxBytes - overhead;
  // Largest prefix (in UTF-16 units, never splitting a surrogate pair) whose JSON form fits.
  const cost = (end: number): number => utf8Length(JSON.stringify(message.slice(0, end))) - 2;
  let lo = 0;
  let hi = message.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (cost(mid) <= budget) lo = mid;
    else hi = mid - 1;
  }
  let end = lo;
  const last = message.charCodeAt(end - 1);
  if (end > 0 && last >= 0xd800 && last <= 0xdbff) end -= 1;
  const truncated = JSON.stringify({ ...base, message: message.slice(0, end) });
  return { json: truncated, bytes: utf8Length(truncated), truncated: true };
}

/** One log event returned by the Datadog search API, flattened. */
export interface DatadogLogEvent {
  /** Datadog log id. */
  id: string;
  /** Event time. */
  timestamp?: Date;
  /** Reserved `host`. */
  host?: string;
  /** Reserved `service`. */
  service?: string;
  /** Reserved `status`. */
  status?: string;
  /** Message text. */
  message: string;
  /** Tags (`env:test`, …). */
  tags: string[];
  /** Custom attributes (`data[].attributes.attributes`), as returned (nested objects). */
  attributes: Record<string, unknown>;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function str(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

/** Parses one `data[]` item of a search response. Returns `undefined` when it is not an object. */
export function parseLogEvent(item: unknown): DatadogLogEvent | undefined {
  if (!isObject(item)) return undefined;
  const attrs = isObject(item.attributes) ? item.attributes : {};
  const event: DatadogLogEvent = {
    id: str(item.id) ?? "",
    message: str(attrs.message) ?? "",
    tags: Array.isArray(attrs.tags)
      ? attrs.tags.filter((t): t is string => typeof t === "string")
      : [],
    attributes: isObject(attrs.attributes) ? attrs.attributes : {},
  };
  const ts = str(attrs.timestamp);
  if (ts !== undefined && !Number.isNaN(Date.parse(ts))) event.timestamp = new Date(ts);
  for (const key of ["host", "service", "status"] as const) {
    const v = str(attrs[key]);
    if (v !== undefined) event[key] = v;
  }
  return event;
}

/** Reads `a.b` from either a flat key `"a.b"` or nested objects `{a: {b}}`. */
function dotted(attrs: Record<string, unknown>, path: string): unknown {
  if (path in attrs) return attrs[path];
  let current: unknown = attrs;
  for (const part of path.split(".")) {
    if (!isObject(current)) return undefined;
    current = current[part];
  }
  return current;
}

function tagValue(tags: readonly string[], key: string): string | undefined {
  const prefix = `${key}:`;
  const tag = tags.find((t) => t.startsWith(prefix));
  return tag === undefined ? undefined : tag.slice(prefix.length);
}

/**
 * Normalizes a search event into a {@link LogRecord} (API.md §6.1): `status` → severity,
 * `service`/`host`/`env:`/`version:` → resource, custom attributes plus `datadog.log_id` and
 * `datadog.tags`; trace ids from `otel.trace_id` / `otel.span_id`, else from `dd.trace_id` /
 * `dd.span_id` (decimal → hex; only the lower 64 bits survive that way).
 */
export function eventToLogRecord(event: DatadogLogEvent): LogRecord {
  const status = event.status ?? "info";
  const resource: Attributes = {};
  if (event.service !== undefined) resource["service.name"] = event.service;
  if (event.host !== undefined) resource["host.name"] = event.host;
  const env = tagValue(event.tags, "env");
  if (env !== undefined) resource["deployment.environment"] = env;
  const version = tagValue(event.tags, "version");
  if (version !== undefined) resource["service.version"] = version;
  const attributes = toAttributes(event.attributes);
  if (event.id !== "") attributes["datadog.log_id"] = event.id;
  if (event.tags.length > 0) attributes["datadog.tags"] = [...event.tags];
  const record: LogRecord = {
    timestamp: event.timestamp ?? new Date(0),
    severity: severityFromStatus(status),
    severityText: status,
    body: event.message,
    attributes,
    resource,
  };
  const traceId =
    datadogIdToHex(dotted(event.attributes, "otel.trace_id"), 32) ??
    datadogIdToHex(dotted(event.attributes, "dd.trace_id"), 32);
  if (traceId !== undefined) {
    record.traceId = traceId;
    const spanId =
      datadogIdToHex(dotted(event.attributes, "otel.span_id"), 16) ??
      datadogIdToHex(dotted(event.attributes, "dd.span_id"), 16);
    if (spanId !== undefined) record.spanId = spanId;
  }
  return record;
}
