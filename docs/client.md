# Client

`DatadogLogsClient` is the entry point. Create one per process (or per set of keys), share it
between threads/tasks and close it on shutdown. The intake exporter, the log clients and the query
client hang off the client (see [Functions](functions.md)).

| Function | Returns | Notes |
| - | - | - |
| constructor / `create` / `new` / `builder()` | client | validates the config (`CONFIG` error, e.g. without an API key) |
| `fromEnv()` | client | config from `GROW_DATADOG_LOGS_*` and `DD_*` variables |
| `health()` | `HealthStatus { healthy, status, details }` | `GET /api/v1/validate` (Datadog has no health endpoint); **never throws** for an unhealthy or unreachable service, only for `CANCELLED` and `CONFIG` |
| `ping()` | latency | authenticated round trip; `AUTHENTICATION` on a bad key |
| `validateApiKey()` | `bool` | `true` for `{"valid": true}`, `false` for 403 |
| `serverVersion()` | — | always `UNSUPPORTED`: Datadog is a SaaS without a version endpoint |
| `close()` | — | idempotent; flushes every log client and stops every tail |

`health()` status values: `ok` (2xx, `details.apiKeyValid`), `unauthorized` (401/403,
`healthy=false`), `degraded` (429), `unhealthy` (5xx), `unreachable` (network error).

## Create, use and close

=== "TypeScript"

    ```ts
    import { DatadogLogsClient, DatadogLogsConfig } from "@grow-codes/datadog-logs";

    const client = new DatadogLogsClient(
      new DatadogLogsConfig({ apiKey: process.env.DD_API_KEY, site: "datadoghq.eu" }),
    );
    const health = await client.health();          // { healthy, status, details }
    const latencyMs = await client.ping();         // number (ms)
    const valid = await client.validateApiKey();   // boolean
    await client.close();                          // safe to call twice

    // Cancellation and per-call deadline: every I/O method accepts { signal }
    const status = await client.health({ signal: AbortSignal.timeout(2_000) });
    ```

=== "Rust"

    ```rust
    use grow_datadog_logs::{DatadogLogsClient, DatadogLogsConfig};

    let config = DatadogLogsConfig::builder()
        .api_key(std::env::var("DD_API_KEY").unwrap_or_default())
        .site("datadoghq.eu")
        .build()?;
    let client = DatadogLogsClient::new(config)?;
    let health = client.health().await?;              // HealthStatus
    let latency = client.ping().await?;               // Duration
    let valid = client.validate_api_key().await?;     // bool
    client.close().await;                             // safe to call twice

    // Cancellation: drop the future, e.g. with a deadline
    let status = tokio::time::timeout(std::time::Duration::from_secs(2), client.health()).await;
    ```

    Without an async runtime, enable the `blocking` feature:

    ```rust
    let client = grow_datadog_logs::blocking::DatadogLogsClient::from_env()?;
    let valid = client.validate_api_key()?;
    ```

=== "Go"

    ```go
    client, err := datadoglogs.NewClient(datadoglogs.Config{
        APIKey: datadoglogs.NewSecret(os.Getenv("DD_API_KEY")),
        Site:   "datadoghq.eu",
    })
    if err != nil {
        return err
    }
    defer client.Close() // safe to call twice

    health, err := client.Health(ctx)         // HealthStatus
    latency, err := client.Ping(ctx)          // time.Duration
    valid, err := client.ValidateAPIKey(ctx)  // bool

    // Cancellation and deadlines come from ctx
    ctx2, cancel := context.WithTimeout(ctx, 2*time.Second)
    defer cancel()
    health, err = client.Health(ctx2)
    ```

=== "Python"

    ```python
    import os
    from grow_datadog_logs import CancelToken, DatadogLogsClient, DatadogLogsConfig

    config = DatadogLogsConfig(api_key=os.environ["DD_API_KEY"], site="datadoghq.eu")
    with DatadogLogsClient(config) as client:
        health = client.health()           # HealthStatus
        latency_ms = client.ping()         # float (ms)
        valid = client.validate_api_key()  # bool

        # Cancel from another thread
        token = CancelToken()
        health = client.health(cancel=token)
    # close() ran; calling it again is safe
    ```

    `AsyncDatadogLogsClient` has the same methods as coroutines (`await client.health()`,
    `await client.aclose()`, `async with`), and honours task cancellation.

=== "Java"

    ```java
    try (DatadogLogsClient client = DatadogLogsClient.builder()
        .apiKey(System.getenv("DD_API_KEY"))
        .site("datadoghq.eu")
        .build()) {
      HealthStatus health = client.health();
      Duration latency = client.ping();
      boolean valid = client.validateApiKey();

      // Async variants return CompletableFuture; cancel(true) cancels the request
      CompletableFuture<HealthStatus> pending = client.healthAsync();
      pending.orTimeout(2, TimeUnit.SECONDS).join();
    } // close() is idempotent
    ```

## Lifecycle rules

- The client is safe for concurrent use. Reuse it: it keeps a connection pool.
- `close()` flushes queued log batches within the configured deadline, stops running tails and is
  safe to call more than once. Every call after `close()` (including `logClient()`) fails with
  `CANCELLED` in all five languages.
- Each language also offers its idiomatic scope: TypeScript `try/finally`, Rust `close().await`
  (plus best-effort `Drop`), Go `defer client.Close()`, Python `with`/`async with`, Java
  try-with-resources.
