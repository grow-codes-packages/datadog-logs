// Created by sdk-kit 0.1.0; owned by this repository.
//! The Datadog Log Management Rust SDK: configuration from the environment, API key
//! validation, sending logs through the batching log client and searching them back.
//!
//! ```sh
//! DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu cargo run
//! ```
//!
//! Against the Docker fakeintake (no search there):
//!
//! ```sh
//! GROW_DATADOG_LOGS_URL=http://datadog-logs:80 DD_API_KEY=test cargo run
//! ```

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_datadog_logs as sdk;
use sdk::core::{ErrorInfo, Hooks};
use sdk::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
use sdk::{
    AggregateLogsOptions, Compute, DatadogLogsClient, DatadogLogsConfig, ErrorCode, GroupBy,
    LogClientOptions, SearchLogsRequest,
};

/// Prints every retry.
struct RetryLogger;

impl Hooks for RetryLogger {
    fn on_error(&self, info: &ErrorInfo) {
        if info.will_retry {
            eprintln!(
                "retrying {} {} (attempt {})",
                info.method, info.url, info.attempt
            );
        }
    }
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("error [{}]: {err}", err.code());
        std::process::exit(1);
    }
}

async fn run() -> sdk::Result<()> {
    // explicit > GROW_DATADOG_LOGS_* > DD_* > defaults; invalid values name the variable only.
    let config = DatadogLogsConfig::builder()
        .service(std::env::var("DD_SERVICE").unwrap_or_else(|_| "grow-example".into()))
        .hooks(Arc::new(RetryLogger))
        .build_from_env()?;
    println!("config: {config:?}"); // keys print as [REDACTED]
    let client = DatadogLogsClient::new(config)?;

    if !client.validate_api_key().await? {
        eprintln!("Datadog rejected the API key (check DD_API_KEY and DD_SITE)");
        std::process::exit(2);
    }
    println!("api key: valid");

    let logs = client.log_client(LogClientOptions::default())?;
    let marker = format!(
        "example-{}",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis()
    );
    let logger = logs.logger(Some("example"), attrs! { "run" => marker.clone() });
    logger.info(
        format!("user signed in {marker}"),
        attrs! { "user.id" => 42 },
    );
    logger.warn(
        format!("disk almost full {marker}"),
        attrs! { "percent" => 91 },
    );
    logs.flush(Some(Duration::from_secs(30))).await?;
    println!("sent: {:?}", logs.stats());

    match client.query() {
        Err(e) if e.code() == ErrorCode::Config => println!("no application key: skipping search"),
        Err(e) => return Err(e),
        Ok(query) => {
            // indexing usually takes seconds; poll gently (the search API is rate limited)
            let found = query
                .wait_for_log(
                    &format!("\"{marker}\""),
                    WaitOptions::default()
                        .timeout(Duration::from_secs(120))
                        .interval(sdk::query::DEFAULT_QUERY_POLL_INTERVAL),
                )
                .await?;
            println!("found: {} {}", found.severity, found.body);
            let page = query
                .search_logs(&SearchLogsRequest::new(format!("\"{marker}\"")).from("now-15m"))
                .await?;
            println!("search: {} events", page.events.len());
            let counts = query
                .aggregate_logs(
                    &[Compute::new("count")],
                    &[GroupBy::new("status")],
                    &AggregateLogsOptions::new(format!("\"{marker}\"")),
                )
                .await?;
            for b in counts.buckets {
                println!("status {}: {:?}", b.by["status"], b.number("c0"));
            }
        }
    }
    client.close().await; // flushes every log client
    Ok(())
}
