// Service-owned: the Datadog logs intake exporter (API.md §5; DECISIONS 4, 7).

import { gzipSync } from "node:zlib";
import {
  type HttpTransport,
  DatadogLogsError,
  UnavailableError,
  ValidationError,
} from "./core/index.js";
import type { DatadogLogsConfig } from "./config.js";
import { datadogRequest, joinUrl } from "./http.js";
import type { ExportOptions, ExportResult, LogExporter, LogRecord } from "./logging/index.js";
import {
  MAX_ENTRIES_PER_REQUEST,
  MAX_LOG_BYTES,
  MAX_PAYLOAD_BYTES,
  serializeEntry,
  toDatadogEntry,
  type EntryDefaults,
} from "./mapping.js";

/** Intake path. */
export const INTAKE_PATH = "/api/v2/logs";

/** Limits of {@link DatadogLogExporter}; values above the Datadog limits are capped. */
export interface DatadogLogExporterOptions {
  /** Entries per request, at most 1000 (default 1000). */
  maxEntriesPerRequest?: number;
  /** Uncompressed payload bytes per request, at most 5 000 000 (default 5 000 000). */
  maxPayloadBytes?: number;
  /** Serialized bytes per entry before truncation, at most 1 000 000 (default 1 000 000). */
  maxLogBytes?: number;
}

/** One intake request body before compression. */
export interface IntakePayload {
  /** The JSON array text. */
  body: string;
  /** Entries in the array. */
  entries: number;
  /** UTF-8 bytes of {@link body}. */
  bytes: number;
}

/** Result of {@link DatadogLogExporter.encode}. */
export interface EncodedBatch {
  /** Request bodies in order. */
  payloads: IntakePayload[];
  /** Records whose metadata alone exceeds the per-log limit (never sent). */
  oversized: number;
  /** Entries whose message was truncated (attribute `grow.truncated: true`). */
  truncated: number;
}

function cap(value: number | undefined, max: number, name: string): number {
  if (value === undefined) return max;
  if (!Number.isInteger(value) || value < 1) {
    throw new ValidationError(`logs.exporter: ${name} must be a positive integer`);
  }
  return Math.min(value, max);
}

/**
 * {@link LogExporter} for `POST {intake}/api/v2/logs`: maps records to Datadog JSON, truncates
 * oversized messages, splits batches at 1000 entries / 5 MB, gzips (when asked) and sends with
 * header `DD-API-KEY` only. The intake POST is retried like an idempotent call (429, 5xx, network
 * errors; duplicates are possible after an ambiguous failure).
 */
export class DatadogLogExporter implements LogExporter {
  readonly #transport: HttpTransport;
  readonly #config: DatadogLogsConfig;
  readonly #defaults: EntryDefaults;
  readonly #maxEntries: number;
  readonly #maxPayload: number;
  readonly #maxLog: number;

  /**
   * @param transport shared transport of the client.
   * @param config client configuration (intake URL, API key, defaults for service/host/tags).
   * @param options request limits.
   */
  constructor(
    transport: HttpTransport,
    config: DatadogLogsConfig,
    options: DatadogLogExporterOptions = {},
  ) {
    this.#transport = transport;
    this.#config = config;
    this.#maxEntries = cap(
      options.maxEntriesPerRequest,
      MAX_ENTRIES_PER_REQUEST,
      "maxEntriesPerRequest",
    );
    this.#maxPayload = cap(options.maxPayloadBytes, MAX_PAYLOAD_BYTES, "maxPayloadBytes");
    this.#maxLog = cap(options.maxLogBytes, MAX_LOG_BYTES, "maxLogBytes");
    const defaults: EntryDefaults = { tags: config.tags, source: config.source };
    for (const key of ["service", "hostname", "env", "version"] as const) {
      const v = config[key];
      if (v !== undefined) defaults[key] = v;
    }
    this.#defaults = defaults;
  }

  /** Maps and splits records into intake request bodies (no I/O). */
  encode(records: readonly LogRecord[]): EncodedBatch {
    const payloads: IntakePayload[] = [];
    let oversized = 0;
    let truncated = 0;
    let parts: string[] = [];
    let bytes = 2;
    const push = (): void => {
      if (parts.length === 0) return;
      payloads.push({ body: `[${parts.join(",")}]`, entries: parts.length, bytes });
      parts = [];
      bytes = 2;
    };
    for (const record of records) {
      const serialized = serializeEntry(toDatadogEntry(record, this.#defaults), this.#maxLog);
      if (serialized === undefined) {
        oversized++;
        continue;
      }
      if (serialized.truncated) truncated++;
      const extra = serialized.bytes + (parts.length > 0 ? 1 : 0);
      if (
        parts.length >= this.#maxEntries ||
        (parts.length > 0 && bytes + extra > this.#maxPayload)
      ) {
        push();
      }
      bytes += serialized.bytes + (parts.length > 0 ? 1 : 0);
      parts.push(serialized.json);
    }
    push();
    return { payloads, oversized, truncated };
  }

  /**
   * Sends the records. Returns `{accepted, rejected, error?}`; a failing request stops the export
   * (later chunks count as rejected). Never throws for service errors.
   */
  async export(records: LogRecord[], opts: ExportOptions): Promise<ExportResult> {
    const { payloads, oversized } = this.encode(records);
    let accepted = 0;
    for (const payload of payloads) {
      try {
        await this.sendPayload(payload.body, opts);
      } catch (err) {
        return {
          accepted,
          rejected: records.length - accepted,
          error:
            err instanceof DatadogLogsError
              ? err
              : new UnavailableError("logs.send: unexpected failure", { cause: err }),
        };
      }
      accepted += payload.entries;
    }
    if (oversized > 0) {
      return {
        accepted,
        rejected: oversized,
        error: new ValidationError(
          `logs.send: ${oversized} record(s) dropped: metadata exceeds ${this.#maxLog} bytes`,
        ),
      };
    }
    return { accepted, rejected: 0 };
  }

  /**
   * Posts one JSON array body to the intake (gzip when `opts.gzip`). Throws the mapped SDK error.
   */
  async sendPayload(body: string, opts: Partial<ExportOptions> = {}): Promise<void> {
    const gzip = opts.gzip ?? true;
    const headers: Record<string, string> = {
      "dd-api-key": this.#config.apiKey?.reveal() ?? "",
      "content-type": "application/json",
    };
    if (gzip) headers["content-encoding"] = "gzip";
    await datadogRequest(this.#transport, {
      method: "POST",
      path: joinUrl(this.#config.intakeUrl, INTAKE_PATH),
      headers,
      body: gzip ? new Uint8Array(gzipSync(Buffer.from(body, "utf8"))) : body,
      idempotent: true,
      operation: "logs.send",
      responseType: "none",
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
  }

  /** Nothing to release; the client owns the transport. */
  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
