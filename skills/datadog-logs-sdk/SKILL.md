---
name: datadog-logs-sdk
description: Add and use the Grow Codes SDK for Datadog Log Management (`datadog-logs`) in TypeScript, Rust, Go, Python or Java projects. Use when code must talk to Datadog Log Management - installing @grow-codes/datadog-logs, the grow-datadog-logs crate or PyPI package, the Go module github.com/grow-codes-packages/datadog-logs/go or Maven codes.grow.packages:datadog-logs, configuring it with GROW_DATADOG_LOGS_* variables, handling DatadogLogsError codes, testing with the bundled fakes, or running the Docker integration suite of this repository.
---

# Datadog Log Management SDK (Grow Codes)

One API for Datadog Log Management in five languages (categories: logging). Pick the package for the
project's language, configure it from the environment, and handle errors by their canonical code.

## 1. Add the package

| Language | Command | Import |
| - | - | - |
| TypeScript | `npm install @grow-codes/datadog-logs` | `import { DatadogLogsClient } from "@grow-codes/datadog-logs"` |
| Rust | `cargo add grow-datadog-logs` (add `tokio` with `macros,rt-multi-thread`) | `use grow_datadog_logs::DatadogLogsClient;` |
| Go | `go get github.com/grow-codes-packages/datadog-logs/go` | `datadoglogs "github.com/grow-codes-packages/datadog-logs/go"` |
| Python | `pip install grow-datadog-logs` (or `uv add grow-datadog-logs`) | `from grow_datadog_logs import DatadogLogsClient` |
| Java | Maven `codes.grow.packages:datadog-logs` (Gradle `implementation("codes.grow.packages:datadog-logs:<version>")`) | `import codes.grow.packages.datadoglogs.DatadogLogsClient;` |

Check the latest version in the README badges before pinning one.

## 2. Configure

Prefer environment configuration so no key is written into code:

```sh
export DD_API_KEY=...          # from your secret store, never committed (required)
export DD_SITE=datadoghq.eu    # API site; default datadoghq.com (us3/us5/ap1/ap2/uk1.datadoghq.com, ddog-gov.com, us2.ddog-gov.com)
export DD_APP_KEY=...          # only for search/aggregate/indexes (logs_read_data, logs_read_config)
export DD_SERVICE=checkout DD_ENV=prod DD_VERSION=1.4.0 DD_TAGS="team:payments,tier:web"
```

Precedence: explicit config value > `GROW_DATADOG_LOGS_*` (`_API_KEY`, `_APP_KEY`, `_SITE`, `_URL`,
`_INTAKE_URL`, `_API_URL`, `_SERVICE`, `_ENV`, `_VERSION`, `_TAGS`, `_HOSTNAME`, `_SOURCE`) >
Datadog variables (`DD_API_KEY`, `DD_APP_KEY`/`DD_APPLICATION_KEY`, `DD_SITE`, `DD_SERVICE`,
`DD_ENV`, `DD_VERSION`, `DD_TAGS`, `DD_HOSTNAME`) > default. `GROW_DATADOG_LOGS_URL` points the
intake and the API at one base URL (proxy, Docker fakeintake).
Other variables: `…_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`, `…_TOTAL_TIMEOUT` (`10s`, `1500`),
`…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`, `…_PROXY`. Environment proxies
(`HTTPS_PROXY`) are ignored unless `proxy` is set. Without an API key the client constructor raises
`CONFIG`; `client.query()` raises `CONFIG` without an application key.

## 3. Use

Create one client per process and close it on shutdown (TypeScript `await client.close()`, Rust
`client.close().await`, Go `defer client.Close()`, Python `with …`/`close()`, Java
try-with-resources).

- `health()` (`GET /api/v1/validate`) never throws for an unhealthy service; check `healthy`
  (`status="unauthorized"` for a rejected key).
- `validateApiKey()` returns `false` for a rejected key; `ping()` raises `AUTHENTICATION`.
  Datadog answers 403 for missing, invalid and under-privileged keys alike: every 403 is
  `AUTHENTICATION`.
- `serverVersion()` and `listLabels()` always raise `UNSUPPORTED`.
- Every Datadog request (including the intake POST) is retried with jitter on 408/429/5xx and
  connection errors; `Retry-After`, else `X-RateLimit-Reset`, sets the wait on 429/503.
- Errors carry `code` (`CONFIG`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CONFLICT`,
  `RATE_LIMITED`, `VALIDATION`, `UNAVAILABLE`, `TIMEOUT`, `UNSUPPORTED`, `CANCELLED`), `retryable`,
  `httpStatus` and `requestId`. Branch on the code, not on the message text. Go:
  `errors.Is(err, datadoglogs.ErrNotFound)`.

### Logging

Create the log client once, send records with `send`/`sendBatch` or use the logger facade
(`logger("component").info("message", attrs)`) or the framework adapter for the language (pino or
winston, `tracing`/`log`, `slog`/zap, `logging`/structlog, Logback/Log4j2/JUL). Records are batched
and redacted; call `close()` on shutdown so the queue is flushed.

<!-- service:skill-usage — the service adds its own functions and examples here. -->

### Datadog functions

| Need | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| ship logs | `client.logClient().logger("api").info("hi")` | `client.log_client(LogClientOptions::default())?` | `client.NewLogClient(datadoglogs.LogClientOptions{})` | `client.log_client().logger("api").info("hi")` | `client.logClient().logger("api").info("hi")` |
| send records now | `await client.exporter.export(records, { gzip: true })` | `client.exporter().export(&records, ExportOptions::new(true)).await?` | `client.Exporter().Export(ctx, records, datadoglogs.ExportOptions{Gzip: true})` | `client.exporter.export(records)` | `client.exporter().export(records, opts).join()` |
| query client | `client.query()` | `client.query()?` | `client.Query()` | `client.query()` | `client.query()` |
| one page | `query.searchLogs({ query, from: "now-1h", limit: 50 })` | `query.search_logs(&SearchLogsRequest::new(q).from("now-1h")).await?` | `query.SearchLogs(ctx, datadoglogs.SearchLogsRequest{Query: q})` | `query.search_logs(q, from_="now-1h")` | `query.searchLogs(SearchLogsRequest.builder().query(q).build())` |
| all pages | `for await (const e of query.iterateLogs({ query }))` | `query.iterate_logs(req)` (Stream) | `for e, err := range query.IterateLogs(ctx, req)` | `for e in query.iterate_logs(q)` | `for (DatadogLogEvent e : query.iterateLogs(req))` |
| counts | `query.aggregateLogs({ aggregation: "count" }, [{ facet: "status" }], { query })` | `query.aggregate_logs(&[Compute::new("count")], &[GroupBy::new("status")], &opts).await?` | `query.AggregateLogs(ctx, []datadoglogs.Compute{{Aggregation: "count"}}, groupBy, opts)` | `query.aggregate_logs(DatadogCompute("count"), [DatadogGroupBy("status")], query=q)` | `query.aggregateLogs(List.of(DatadogCompute.of("count")), List.of(DatadogGroupBy.of("status")), opts)` |
| test helper | `query.waitForLog('"run-1"', { timeoutMs: 120_000 })` | `query.wait_for_log(q, WaitOptions::default().interval(DEFAULT_QUERY_POLL_INTERVAL)).await?` | `query.WaitForLog(ctx, q, datadoglogs.WaitForLogOptions{Timeout: 2 * time.Minute})` | `query.wait_for_log(q, timeout_s=120)` | `query.waitForLog(q)` |

Rules an agent must know:

- Logs go to `https://http-intake.logs.<site>/api/v2/logs`; the SDK splits batches at 1,000
  entries / 5 MB, truncates records over 1 MB (`grow.truncated=true`) and sends trace ids as
  `dd.trace_id`/`dd.span_id` (decimal lower 64 bits). User attributes named like reserved keys
  (`service`, `status`, `ddtags`, …) are sent as `attr.<name>`.
- Set resource `service.name` / `deployment.environment` / `service.version` / `host.name` (or the
  config `service`/`env`/`version`/`hostname`) instead of attributes; they become `service`,
  `ddtags` and `hostname`.
- The search API is rate limited: poll at 5 s or slower (Rust and Java option types default to 1 s,
  pass `DEFAULT_QUERY_POLL_INTERVAL` / `DatadogLogsQuery.DEFAULT_POLL_INTERVAL`). Indexing takes
  seconds, so give `waitForLog` a generous timeout and search for a run-unique marker.
- A retried intake request can store a batch twice (Datadog has no idempotency key).
- Python: `DatadogLogsConfig.from_env(environ, /, **overrides)` (mapping is positional-only);
  do not `dataclasses.replace(cfg, site=…)` (keeps the old URLs), build a new config instead.

## 4. Test code that uses the SDK

Use the in-memory fakes from the testing module instead of a real Datadog account in unit tests:
TypeScript `@grow-codes/datadog-logs/testing`, Rust feature `testing`, Go package `testkit`, Python
`grow_datadog_logs.testing`, Java `codes.grow.packages.datadoglogs.testing`.

## 5. Work on this repository

Read `AGENTS.md` first. Short version: the five languages keep the same API; files with the
`Generated by sdk-kit` header are changed in sdk-kit, not here; `make lint coverage` must pass
(≥ 90 % lines and branches); `make test-docker LANGS="<lang>"` runs the integration suite against
`datadog/fakeintake:v79` (intake round trips; search/aggregate/indexes only as wire stubs, see
`docs/compatibility.md`).
