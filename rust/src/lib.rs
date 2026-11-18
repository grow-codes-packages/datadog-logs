// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-datadog-logs
//!
//! Rust SDK for [Datadog Log Management](https://grow-codes-packages.github.io/datadog-logs/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_datadog_logs::Result<()> {
//! use grow_datadog_logs::{DatadogLogsClient, DatadogLogsConfig};
//!
//! let client = DatadogLogsClient::new(DatadogLogsConfig::from_env()?)?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tracing-layer` and `log-bridge` (logging adapters, on by default)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::DEFAULT_URL;

pub use crate::client::DatadogLogsClient;

pub use crate::config::DatadogLogsConfig;

pub use crate::config::DatadogLogsConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type DatadogLogsError = Error;
