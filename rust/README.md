# grow-datadog-logs (Rust)

Rust SDK for Datadog Log Management by [Grow Codes](https://grow.codes). Async on tokio, with an optional
blocking client.

```toml
[dependencies]
grow-datadog-logs = "0.1.0"
# blocking client:   features = ["blocking"]
# test fakes/runner: features = ["testing"] (dev-dependency)
```

```rust,no_run
use grow_datadog_logs::logging::{attrs, LogClient};
use grow_datadog_logs::{DatadogLogsClient, DatadogLogsConfig, LogClientOptions, SearchLogsRequest};

#[tokio::main]
async fn main() -> grow_datadog_logs::Result<()> {
    // explicit > GROW_DATADOG_LOGS_* > DD_API_KEY / DD_APP_KEY / DD_SITE / DD_SERVICE … > defaults
    let client = DatadogLogsClient::new(DatadogLogsConfig::from_env()?)?;
    assert!(client.validate_api_key().await?);

    // logs intake: gzip, batches of at most 1000 logs / 5 MB, messages over 1 MB truncated
    let logs = client.log_client(LogClientOptions::default())?;
    logs.logger(Some("checkout"), attrs! {}).info("order placed", attrs! { "order.id" => 42 });
    logs.flush(None).await?;

    // Logs Search v2 (needs DD_APP_KEY)
    let page = client
        .query()?
        .search_logs(&SearchLogsRequest::new("service:checkout").from("now-15m").limit(50))
        .await?;
    println!("{} logs", page.events.len());
    client.close().await; // flushes every log client
    Ok(())
}
```

- Ingest: `log_client` (batching `LogClient`, `Logger`, `LogLayer` for `tracing`, `LogBridge`
  for `log`), `exporter` (`DatadogLogExporter`), reserved attributes (`ddsource`, `ddtags`,
  `hostname`, `service`, `status`, `dd.trace_id`, `dd.span_id`), `resolve_site`.
- Query (`client.query()`): `search_logs`, `iterate_logs` (cursor stream), `aggregate_logs`,
  `list_indexes`, and `LogQueryClient` (`search`, `wait_for_log`, polling `tail`,
  `list_streams`; `list_labels` is `UNSUPPORTED`).
- `health`, `ping`, `validate_api_key` (`GET /api/v1/validate`); `server_version` is
  `UNSUPPORTED` (SaaS).
- Errors: one `Error` enum with `code()`, `http_status()`, `request_id()`, `is_retryable()`,
  `retry_after()`; messages never contain credentials.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation (drop the future or pass a `CancellationToken`), request hooks, `tracing` debug
  events.
- MSRV 1.88. Licence Apache-2.0. Documentation: https://grow-codes-packages.github.io/datadog-logs/

Development: `make deps build lint test coverage license pack docs` (see `Makefile`).
