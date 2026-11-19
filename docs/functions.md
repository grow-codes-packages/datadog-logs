# Functions

The Datadog Log Management API of this SDK. Every function exists in all five languages with the
same behaviour, defaults and error codes; names follow each language's convention. Each module page
shows every function with an example per language and the request it sends.

| Module | Page | What it covers |
| - | - | - |
| Client and status | [Client and status](functions/client.md) | `fromEnv`, `health`, `ping`, `validateApiKey`, `serverVersion`, `close`, site resolver, `parseTags` |
| Logs intake | [Intake](functions/intake.md) | `exporter.export`, the reserved attribute mapping, batching limits, truncation |
| Log client | [Log client](functions/log-client.md) | `logClient` (`send`, `sendBatch`, `flush`, `stats`, `logger`, `close`) and framework adapters |
| Logs search | [Search and aggregate](functions/search.md) | `query()`, `searchLogs`, `iterateLogs`, `aggregateLogs`, `listIndexes` |
| Logging category query | [Query, tail and waitForLog](functions/query.md) | `search`, `tail`, `waitForLog`, `listStreams`, `listLabels` |

## Names per language

| Function | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| from environment | `DatadogLogsClient.fromEnv()` | `DatadogLogsClient::from_env()` | `datadoglogs.NewClientFromEnv()` | `DatadogLogsClient.from_env()` | `DatadogLogsClient.fromEnv()` |
| health | `health()` | `health()` | `Health(ctx)` | `health()` | `health()` |
| ping | `ping()` → ms | `ping()` → `Duration` | `Ping(ctx)` → `time.Duration` | `ping()` → ms | `ping()` → `Duration` |
| key check | `validateApiKey()` | `validate_api_key()` | `ValidateAPIKey(ctx)` | `validate_api_key()` | `validateApiKey()` |
| server version | `serverVersion()` | `server_version()` | `ServerVersion(ctx)` | `server_version()` | `serverVersion()` |
| intake exporter | `client.exporter` | `client.exporter()` | `client.Exporter()` | `client.exporter` | `client.exporter()` |
| log client | `logClient(opts)` | `log_client(opts)` | `NewLogClient(opts)` | `log_client(...)` | `logClient(opts)` |
| query client | `query()` | `query()` | `Query()` | `query()` | `query()` |
| search page | `searchLogs(params)` | `search_logs(&req)` | `SearchLogs(ctx, req)` | `search_logs(query, ...)` | `searchLogs(req)` |
| all pages | `iterateLogs(params)` (async iterator) | `iterate_logs(req)` (`Stream`) | `IterateLogs(ctx, req)` (`iter.Seq2`) | `iterate_logs(query, ...)` (iterator) | `iterateLogs(req)` (`Iterable`) |
| aggregate | `aggregateLogs(compute, groupBy, opts)` | `aggregate_logs(&compute, &group_by, &opts)` | `AggregateLogs(ctx, compute, groupBy, opts)` | `aggregate_logs(compute, group_by, ...)` | `aggregateLogs(compute, groupBy, opts)` |
| indexes | `listIndexes()` | `list_indexes()` | `ListIndexes(ctx)` | `list_indexes()` | `listIndexes()` |
| category search | `search(query, opts)` | `search(query, opts)` | `Search(ctx, query, opts)` | `search(query, ...)` | `search(query, opts)` |
| tail | `tail(query, onRecord, opts)` | `tail(query, on_record, opts)` | `Tail(ctx, query, onRecord, opts)` | `tail(query, on_record, ...)` | `tail(query, onRecord, opts)` |
| wait for a log | `waitForLog(query, opts)` | `wait_for_log(query, opts)` | `WaitForLog(ctx, query, opts)` | `wait_for_log(query, ...)` | `waitForLog(query[, opts])` |
| streams | `listStreams()` | `list_streams()` | `ListStreams(ctx)` | `list_streams()` | `listStreams()` |
| labels | `listLabels()` | `list_labels()` | `ListLabels(ctx)` | `list_labels()` | `listLabels()` |

Every I/O method takes a cancellation handle: TypeScript `{ signal }`, Rust dropping the future,
Go `ctx`, Python `cancel=` (or the `AsyncDatadogLogsClient`), Java the `…Async` variant.

## Wire facts that apply everywhere

- **Two hosts per site.** Logs go to `https://http-intake.logs.<site>`; validate, search,
  aggregate and indexes go to `https://api.<site>`. `url` (or `intakeUrl` + `apiUrl`) overrides both.
- **Two keys.** `DD-API-KEY` on every request; `DD-APPLICATION-KEY` only on the query API.
- **403 means the key.** Datadog answers 403 for a missing, invalid or under-privileged key; the
  SDK raises `AUTHENTICATION` (status 403). Error bodies are lists of strings
  (`{"errors":["Forbidden"]}`); the SDK puts them into the error message after redaction.
- **Rate limits.** 429 and 503 honour `Retry-After`, else `X-RateLimit-Reset`.
- **The intake has limits** the SDK enforces before sending: 1,000 entries and 5 MB
  (uncompressed) per request, 1 MB per log (larger bodies are truncated and marked
  `grow.truncated=true`).
- **Trace ids** are sent as `dd.trace_id` / `dd.span_id`: the decimal string of the lower 64 bits of
  the OpenTelemetry ids (the Datadog convention); the full ids go into `otel.trace_id` /
  `otel.span_id` (hex).
- **Search times** accept ISO-8601, epoch milliseconds or Datadog date math (`now-15m`); the SDK
  formats native time values (`Date`, `SystemTime`, `time.Time`, `datetime`, `Instant`) for you.
