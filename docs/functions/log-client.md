# Log client

`logClient(options)` returns a batching `DatadogLogClient` (the category `LogClient`) over the
[intake exporter](intake.md). Records are queued and sent in the background; the caller is never
blocked by default. The generic behaviour (batch options, drop policies, `stats()`, redaction,
trace correlation, `Logger` facade) is described in [Logging](../logging.md); this page lists what
is specific to Datadog.

| Function | Description |
| - | - |
| `logClient(options)` | new batching client; the resource defaults to the configured `service` → `service.name`, `version` → `service.version`, `env` → `deployment.environment`, `hostname` → `host.name` (keys in `options.resource` win) |
| `send(record)` / `sendBatch(records)` | enqueue; never block by default |
| `flush(timeout)` | send everything queued now |
| `stats()` | `queued`, `sent`, `dropped`, `failed`, `retries` |
| `logger(name, attributes)` | `Logger` facade: `trace` … `fatal`, `child(attributes)` |
| `close()` | drains the queue; `client.close()` closes every log client it made |

Datadog specifics:

- `maxBatchSize` and `maxBatchBytes` are capped at the intake limits (1,000 entries, 5,000,000
  bytes); gzip is on by default.
- The default `RedactionProcessor` removes `password`, `token`, `authorization`, `secret`,
  `cookie`, `api_key` values before a record leaves the process.
- The active OpenTelemetry span fills `traceId`/`spanId`, sent as `dd.trace_id`/`dd.span_id` so the
  log is linked to the APM trace.

=== "TypeScript"

    ```ts
    const logs = client.logClient({ batch: { flushIntervalMs: 2_000 } });
    const log = logs.logger("payments", { region: "eu" });
    log.info("order paid", { orderId: 42 });
    log.child({ step: "refund" }).warn("refund slow", { durationMs: 1234 });
    await logs.flush();
    console.log(logs.stats());

    // pino / winston
    import { createPinoTransport, WinstonTransport } from "@grow-codes/datadog-logs";
    ```

=== "Rust"

    ```rust
    use std::sync::Arc;
    use grow_datadog_logs::logging::{attrs, LogBridge, LogClient, LogLayer};

    let logs = client.log_client(LogClientOptions::default())?;
    let log = logs.logger(Some("payments"), attrs! { "region" => "eu" });
    log.info("order paid", attrs! { "orderId" => 42 });
    logs.flush(Some(std::time::Duration::from_secs(10))).await?;
    println!("{:?}", logs.stats());

    // tracing-subscriber layer and log::Log bridge (features "tracing-layer", "log-bridge")
    let shared: Arc<dyn LogClient> = Arc::new(logs.clone());
    let layer = LogLayer::new(shared.clone());
    LogBridge::new(shared).init().ok();
    ```

=== "Go"

    ```go
    logs, err := client.NewLogClient(datadoglogs.LogClientOptions{})
    logger := logs.Logger("payments", map[string]datadoglogs.AttrValue{"region": "eu"})
    logger.Info(ctx, "order paid", "orderId", 42)
    _ = logs.Flush(ctx)
    fmt.Printf("%+v\n", logs.Stats())

    // log/slog and zap (module github.com/grow-codes-packages/datadog-logs/go/zapadapter)
    slog.SetDefault(slog.New(datadoglogs.NewSlogHandler(logs, nil)))
    zl := zapadapter.NewLogger(logs, zapadapter.Options{})
    ```

=== "Python"

    ```python
    import logging
    from grow_datadog_logs import BatchOptions, LogHandler, structlog_processor

    logs = client.log_client(BatchOptions(flush_interval_s=2.0))
    log = logs.logger("payments", {"region": "eu"})
    log.info("order paid", {"orderId": 42})
    logs.flush(10)
    print(logs.stats())

    # stdlib logging and structlog
    logging.getLogger("app").addHandler(LogHandler(logs))
    processor = structlog_processor(logs)
    ```

=== "Java"

    ```java
    DatadogLogClient logs = client.logClient();
    Logger log = logs.logger("payments", Map.of("region", "eu"));
    log.info("order paid", Map.of("orderId", 42));
    logs.flush();
    System.out.println(logs.stats());

    // Logback (GrowLogbackAppender), Log4j2 (GrowLog4j2Appender), JUL (GrowJulHandler):
    // see the Logging page for the configuration snippets.
    ```
