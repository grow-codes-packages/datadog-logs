// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! Blocking wrapper (feature `blocking`). Each client owns a current-thread tokio runtime.
//!
//! Do not use it from inside an async runtime (calls would panic); use the async client there.
//! Logs are sent with [`DatadogLogsClient::export`] (one synchronous intake call per batch,
//! no background batching).

use std::future::Future;
use std::time::Duration;

use crate::core::{Error, HealthStatus, Result};
use crate::logging::{
    ExportOptions, ExportResult, LogExporter, LogQueryClient, LogRecord, LogSearchResult,
    SearchOptions, WaitOptions,
};
use crate::query::{
    AggregateLogsOptions, AggregateLogsResult, Compute, DatadogLogsQuery as AsyncQuery, GroupBy,
    LogIndex, SearchLogsPage, SearchLogsRequest,
};
use crate::{DatadogLogsClient as AsyncClient, DatadogLogsConfig};

/// Blocking client for Datadog Log Management with the same methods as the async client.
#[derive(Debug)]
pub struct DatadogLogsClient {
    inner: AsyncClient,
    runtime: tokio::runtime::Runtime,
}

impl DatadogLogsClient {
    /// Creates a client from a configuration.
    pub fn new(config: DatadogLogsConfig) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| Error::config("cannot start the tokio runtime").with_source(e))?;
        let inner = {
            let _guard = runtime.enter();
            AsyncClient::new(config)?
        };
        Ok(DatadogLogsClient { inner, runtime })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(DatadogLogsConfig::from_env()?)
    }

    /// The wrapped async client.
    pub fn async_client(&self) -> &AsyncClient {
        &self.inner
    }

    /// Runs a future of the async API to completion on this client's runtime.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }

    /// See [`AsyncClient::health`].
    pub fn health(&self) -> Result<HealthStatus> {
        self.block_on(self.inner.health())
    }

    /// See [`AsyncClient::ping`].
    pub fn ping(&self) -> Result<Duration> {
        self.block_on(self.inner.ping())
    }

    /// See [`AsyncClient::validate_api_key`].
    pub fn validate_api_key(&self) -> Result<bool> {
        self.block_on(self.inner.validate_api_key())
    }

    /// See [`AsyncClient::server_version`].
    pub fn server_version(&self) -> Result<String> {
        self.block_on(self.inner.server_version())
    }

    /// Sends records to the intake now (mapping, truncation, splitting and gzip as the
    /// exporter does). See [`crate::DatadogLogExporter`].
    pub fn export(&self, records: &[LogRecord], gzip: bool) -> Result<ExportResult> {
        let exporter = self.inner.exporter();
        self.block_on(exporter.export(records, ExportOptions::new(gzip)))
    }

    /// The blocking query client. `CONFIG` without an application key.
    pub fn query(&self) -> Result<DatadogLogsQuery<'_>> {
        Ok(DatadogLogsQuery {
            client: self,
            inner: self.inner.query()?,
        })
    }

    /// See [`AsyncClient::close`]. Idempotent.
    pub fn close(&self) {
        self.block_on(self.inner.close());
    }
}

impl Drop for DatadogLogsClient {
    fn drop(&mut self) {
        self.inner.transport().close();
    }
}

/// Blocking query client; see [`crate::DatadogLogsQuery`].
#[derive(Debug)]
pub struct DatadogLogsQuery<'a> {
    client: &'a DatadogLogsClient,
    inner: AsyncQuery,
}

impl DatadogLogsQuery<'_> {
    /// The wrapped async query client.
    pub fn async_query(&self) -> &AsyncQuery {
        &self.inner
    }

    /// See [`AsyncQuery::search_logs`].
    pub fn search_logs(&self, req: &SearchLogsRequest) -> Result<SearchLogsPage> {
        self.client.block_on(self.inner.search_logs(req))
    }

    /// Collects [`AsyncQuery::iterate_logs`] (set `req.limit` to bound it).
    pub fn iterate_logs(&self, req: SearchLogsRequest) -> Result<Vec<crate::LogEvent>> {
        use futures::TryStreamExt;
        self.client
            .block_on(self.inner.iterate_logs(req).try_collect::<Vec<_>>())
    }

    /// See [`AsyncQuery::aggregate_logs`].
    pub fn aggregate_logs(
        &self,
        compute: &[Compute],
        group_by: &[GroupBy],
        opts: &AggregateLogsOptions,
    ) -> Result<AggregateLogsResult> {
        self.client
            .block_on(self.inner.aggregate_logs(compute, group_by, opts))
    }

    /// See [`AsyncQuery::list_indexes`].
    pub fn list_indexes(&self) -> Result<Vec<LogIndex>> {
        self.client.block_on(self.inner.list_indexes())
    }

    /// See [`LogQueryClient::list_streams`].
    pub fn list_streams(&self) -> Result<Vec<String>> {
        self.client.block_on(self.inner.list_streams())
    }

    /// See [`LogQueryClient::search`].
    pub fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        self.client.block_on(self.inner.search(query, opts))
    }

    /// See [`LogQueryClient::wait_for_log`].
    pub fn wait_for_log(&self, query: &str, opts: WaitOptions) -> Result<LogRecord> {
        self.client.block_on(self.inner.wait_for_log(query, opts))
    }
}
