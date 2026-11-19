// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-datadog-logs
//!
//! Rust SDK for [Datadog Log Management](https://grow-codes-packages.github.io/datadog-logs/) by
//! Grow Codes: the logs intake (`POST /api/v2/logs`, gzip, 1000 entries / 5 MB batches, 1 MB per
//! log), Logs Search v2 with cursor pagination, log aggregation, index listing and API key checks.
//! Not affiliated with Datadog, Inc.
//!
//! ```no_run
//! # async fn demo() -> grow_datadog_logs::Result<()> {
//! use grow_datadog_logs::logging::{attrs, LogClient};
//! use grow_datadog_logs::{DatadogLogsClient, DatadogLogsConfig, LogClientOptions};
//!
//! // DD_API_KEY, DD_SITE, DD_SERVICE, … or GROW_DATADOG_LOGS_* variables
//! let client = DatadogLogsClient::new(DatadogLogsConfig::from_env()?)?;
//! assert!(client.validate_api_key().await?);
//! let logs = client.log_client(LogClientOptions::default())?;
//! logs.logger(Some("checkout"), attrs! {}).info("order placed", attrs! { "order.id" => 42 });
//! client.close().await; // flushes
//! # Ok(())
//! # }
//! ```
//!
//! Search (needs `DD_APP_KEY`):
//!
//! ```no_run
//! # async fn demo(client: grow_datadog_logs::DatadogLogsClient) -> grow_datadog_logs::Result<()> {
//! use grow_datadog_logs::SearchLogsRequest;
//! let query = client.query()?;
//! let page = query
//!     .search_logs(&SearchLogsRequest::new("service:checkout status:error").from("now-1h").limit(50))
//!     .await?;
//! for record in page.records() {
//!     println!("{} {}", record.severity, record.body);
//! }
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner), `tracing-layer` and `log-bridge` (logging adapters, on by default).
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;
mod http;
mod intake;
pub mod mapping;
pub mod query;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::client::{DatadogLogClient, DatadogLogsClient, LogClientOptions, VALIDATE_PATH};
pub use crate::config::{
    parse_tags, resolve_site, DatadogLogsConfig, DatadogLogsConfigBuilder, SiteEndpoints,
    DEFAULT_INTAKE_URL, DEFAULT_SITE, DEFAULT_SOURCE, DEFAULT_URL, SITES,
};
pub use crate::http::{map_datadog_error, HEADER_API_KEY, HEADER_APP_KEY, RATE_LIMIT_RESET_HEADER};
pub use crate::intake::{DatadogLogExporter, EncodedBatch, IntakePayload, INTAKE_PATH};
pub use crate::mapping::LogEvent;
pub use crate::query::{
    AggregateBucket, AggregateLogsOptions, AggregateLogsResult, Compute, DatadogLogsQuery, GroupBy,
    GroupByHistogram, GroupBySort, LogIndex, LogsSort, QueryMeta, SearchLogsPage,
    SearchLogsRequest, SearchWarning, TimeseriesPoint,
};

/// Service-prefixed alias of [`Error`].
pub type DatadogLogsError = Error;
