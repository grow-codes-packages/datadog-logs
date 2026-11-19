# Compatibility

## Runtimes

| Language | Supported | Tested in CI |
| - | - | - |
| TypeScript | Node.js ≥ 22.12, ESM and CommonJS | Node 22, 24 |
| Rust | MSRV 1.88, edition 2021, tokio | stable, 1.88 |
| Go | Go ≥ 1.26 | 1.26, 1.27 |
| Python | ≥ 3.10 | 3.10, 3.13, 3.14 |
| Java | ≥ 17 (Java 17 bytecode) | Temurin 17, 21, 25 |

## Datadog Log Management

Datadog is a SaaS without versions. The SDK uses the Logs intake API v2 (`/api/v2/logs`), the Logs
Search API v2 (`/api/v2/logs/events/search`, `/api/v2/logs/analytics/aggregate`), the key
validation endpoint (`/api/v1/validate`) and the log index configuration
(`/api/v1/logs/config/indexes`) on every Datadog site (`datadoghq.com`, `us3`, `us5`, `ap1`, `ap2`,
`uk1`, `datadoghq.eu`, `ddog-gov.com`, `us2.ddog-gov.com`).

The Docker suite runs against `datadog/fakeintake:v79` (pinned by digest; the stack catalog pins no
image for this managed service). fakeintake re-publishes its moving `vN` tags, so the frozen `v79`
is pinned until a deliberate bump.

## Functions the emulator cannot serve

fakeintake records intake payloads but has no search engine, index configuration or real key
checks. These functions are covered by **unit tests with recorded Datadog fixtures** (from the
official OpenAPI spec and probes of the real API without valid keys) and called in Docker against
**wire stubs** (a fakeintake override answers with a fixture; the request the SDK sent is read
back from the capture and asserted). A live round trip needs a Datadog sandbox organisation; the
live job is **pending sandbox keys from the owner**.

| Function | Languages | Reason |
| - | - | - |
| `searchLogs`, `iterateLogs`, `search` | all | SaaS only: no search engine in fakeintake |
| `aggregateLogs` | all | SaaS only |
| `listIndexes`, `listStreams` | all | SaaS only (needs `logs_read_config`) |
| `tail`, `waitForLog` | all | built on search; polled against a stub that returns a fixed page |
| `validateApiKey` / `ping` with a real key | all | the bootstrap stubs `/api/v1/validate` with `{"valid": true}` for any key; `false`/403 is covered by an injected 403 |
| `Retry-After` / `X-RateLimit-Reset` timing | all | fakeintake overrides cannot set response headers; unit tests only |
| Datadog-side truncation, remapping, indexing delay | all | only the live job can observe them; the SDK-side limits (1,000 entries, 5 MB, 1 MB truncation) are asserted on the captured payloads |
| `serverVersion` | all | always `UNSUPPORTED`: Datadog reports no version |
| `listLabels` | all | always `UNSUPPORTED`: Datadog has no attribute listing API |

fakeintake quirks the suite works around: its typed `format=json` view rejects the whole route
once any payload is a JSON object or has a string timestamp (the suite reads the raw capture, and
the SDK always sends arrays with integer millisecond timestamps); an un-stubbed search `POST`
answers 200 `{"errors":[]}`, which the SDK reports as `UNAVAILABLE` (protocol error), never as an
empty result.

## Skipped conformance scenarios

No scenario is skipped. In `core.health` the `serverVersion` step (requires capability `version`)
is not run, because Datadog reports no server version and `serverVersion()` raises
`UNSUPPORTED`.

The logging scenarios (`logging.roundtrip`, `logging.batch`, `logging.redaction`) run against
fakeintake with a query client that reads the raw intake capture (capability `query`).

## Behaviour that is the same everywhere (binding decisions)

- Environment: `GROW_DATADOG_LOGS_*` first, then `DD_API_KEY`, `DD_APP_KEY` / `DD_APPLICATION_KEY`,
  `DD_SITE`, `DD_SERVICE`, `DD_ENV`, `DD_VERSION`, `DD_TAGS`, `DD_HOSTNAME`.
- `health()`/`ping()`/`validateApiKey()` use `GET /api/v1/validate`; every Datadog 403 is
  `AUTHENTICATION` (status 403).
- Every Datadog request (including the intake `POST`) is retried on 408, 429, 500, 502, 503, 504
  and network errors; on 429 **and 503** `X-RateLimit-Reset` is the retry hint when
  `Retry-After` is missing.
- `aggregateLogs` always sends the compute `type`, `"total"` unless `timeseries` is given.
- `ddtags`: `env:`, `version:`, then `tags`, de-duplicated; whitespace (Unicode) and commas
  inside one tag become `_`.
- `dd.trace_id` / `dd.span_id`: decimal string of the lower 64 bits; full hex ids in
  `otel.trace_id` / `otel.span_id`.
- Records over 1 MB are truncated with `grow.truncated=true`; batches are split at 1,000 entries /
  5 MB; user attributes named like reserved keys are sent as `attr.<name>`; `ddsource` defaults to
  `grow-sdk`.
- `tail` starts at "now" unless `start` is given and polls every 5 s by default; `waitForLog`
  polls every 5 s with a 30 s timeout.
- Every call on a closed client (including `logClient()`) raises `CANCELLED`.

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Known differences:

| Topic | Difference |
| - | - |
| Poll interval defaults of `tail` / `waitForLog` | TypeScript, Go and Python default to 5 s when the interval is not given. The Rust `TailOptions` / `WaitOptions` and the Java `TailOptions` / `WaitForLogOptions` are shared category types whose interval defaults to 1 s and cannot express "unset". Rust exports `query::DEFAULT_QUERY_POLL_INTERVAL` (5 s) to pass explicitly; Java's `waitForLog(String)` uses `DatadogLogsQuery.DEFAULT_POLL_INTERVAL` (5 s), the other overloads use the interval you pass. |
| Python `from_env` | `DatadogLogsConfig.from_env(environ, /, **overrides)`: the environment mapping is **positional-only**, because the config has a field called `env` (`from_env({"DD_API_KEY": "k"}, env="prod")`). |
| Python `dataclasses.replace` | The config derives `intake_url`, `api_url` and `url` from `site` when it is built, so `dataclasses.replace(cfg, site="datadoghq.eu")` keeps the **old URLs**. Build a new config (`DatadogLogsConfig.from_env(site=…)` or `DatadogLogsConfig(api_key=…, site=…)`) or replace the URL fields too. |
| Python async tail | `AsyncDatadogLogsQuery.tail` uses its own polling loop that lets task cancellation through; the generic sdk-kit async tail catches the SDK `CancelledError` (which is also an `Exception`) and `stop()` could hang. |
| TypeScript transport after `close()` | The generated core transport reports a closed client as `CONFIG`; the service layer checks first and raises `CANCELLED` like the other languages. Raw `client.transport` calls keep the core behaviour. |
| Tail error handling | Go, Rust and Java end a tail on a non-retryable error (`Err()` / `onError`); TypeScript and Python report every error to `onError` and keep polling. |
| Java environment | Java cannot change the process environment, so `loadEnv` sets **system properties** instead and returns the names it set. |
