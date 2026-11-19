// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `DatadogLogsClient`: entry point of the SDK (SPEC §2.4, API.md §4 and §7.1).

use std::sync::{Arc, Once};
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde_json::Value;

use crate::config::{DatadogLogsConfig, SiteEndpoints};
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, HealthStatus, HttpTransport, Response, Result};
use crate::http::{send, HEADER_API_KEY};
use crate::intake::DatadogLogExporter;
use crate::logging::{
    Attributes, BatchOptions, BatchingLogClient, LogClient, RedactionProcessor, Severity,
};
use crate::mapping::{MAX_ENTRIES_PER_REQUEST, MAX_PAYLOAD_BYTES};
use crate::query::DatadogLogsQuery;

/// Path of the API key check used by `health`, `ping` and `validate_api_key`.
pub const VALIDATE_PATH: &str = "/api/v1/validate";

/// The batching [`LogClient`] returned by [`DatadogLogsClient::log_client`].
pub type DatadogLogClient = BatchingLogClient;

static PLAIN_HTTP_WARNING: Once = Once::new();

/// Options of [`DatadogLogsClient::log_client`].
#[derive(Clone)]
pub struct LogClientOptions {
    /// Batch options; `max_batch_size` and `max_batch_bytes` are capped at the intake limits
    /// (1000 records, 5 000 000 bytes).
    pub batch: BatchOptions,
    /// Resource attributes; they win over the config defaults (`service.name` ← service,
    /// `service.version` ← version, `deployment.environment` ← env, `host.name` ← hostname).
    pub resource: Attributes,
    /// Redaction applied to every record (default [`RedactionProcessor::default`]); `None`
    /// disables it.
    pub redaction: Option<RedactionProcessor>,
    /// Minimum severity of loggers made by [`LogClient::logger`] (default `Info`).
    pub min_severity: Severity,
}

impl Default for LogClientOptions {
    fn default() -> Self {
        LogClientOptions {
            batch: BatchOptions::default(),
            resource: Attributes::new(),
            redaction: Some(RedactionProcessor::default()),
            min_severity: Severity::Info,
        }
    }
}

impl std::fmt::Debug for LogClientOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LogClientOptions")
            .field("batch", &self.batch)
            .field("resource", &self.resource)
            .field("redaction", &self.redaction.is_some())
            .field("min_severity", &self.min_severity)
            .finish()
    }
}

impl LogClientOptions {
    /// Sets the batch options.
    pub fn batch(mut self, batch: BatchOptions) -> Self {
        self.batch = batch;
        self
    }
    /// Adds one resource attribute.
    pub fn resource_attribute(
        mut self,
        key: impl Into<String>,
        value: impl Into<crate::logging::AttrValue>,
    ) -> Self {
        self.resource.insert(key.into(), value.into());
        self
    }
}

/// Async client for Datadog Log Management. Cheap to clone (clones share one connection pool).
///
/// Two hosts are used: the logs intake ([`log_client`](Self::log_client),
/// [`exporter`](Self::exporter)) and the API ([`health`](Self::health), [`ping`](Self::ping),
/// [`validate_api_key`](Self::validate_api_key), [`query`](Self::query)).
/// [`close`](Self::close) flushes every log client made by `log_client` and stops tails.
#[derive(Debug, Clone)]
pub struct DatadogLogsClient {
    inner: Arc<Inner>,
}

#[derive(Debug)]
pub(crate) struct Inner {
    pub(crate) config: DatadogLogsConfig,
    pub(crate) transport: HttpTransport,
    pub(crate) on_close: CloseGroup,
}

impl DatadogLogsClient {
    /// Creates a client from a configuration.
    pub fn new(config: DatadogLogsConfig) -> Result<Self> {
        config.validate()?;
        let transport = HttpTransport::new(config.common())?;
        transport.add_known_secret(config.api_key());
        if let Some(app_key) = config.app_key() {
            transport.add_known_secret(app_key);
        }
        if config.intake_url().scheme() == "http" || config.api_url().scheme() == "http" {
            PLAIN_HTTP_WARNING.call_once(|| {
                tracing::warn!(
                    target: "grow_datadog_logs",
                    "a Datadog endpoint uses plain http; the API key travels unencrypted (use only for local test intakes)"
                );
            });
        }
        Ok(DatadogLogsClient {
            inner: Arc::new(Inner {
                config,
                transport,
                on_close: CloseGroup::new(),
            }),
        })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(DatadogLogsConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &DatadogLogsConfig {
        &self.inner.config
    }

    /// The effective intake and API base URLs.
    pub fn endpoints(&self) -> SiteEndpoints {
        self.inner.config.endpoints()
    }

    /// The HTTP transport (for service modules).
    pub fn transport(&self) -> &HttpTransport {
        &self.inner.transport
    }

    /// Registers work that [`DatadogLogsClient::close`] runs (flush, stop watchers, …).
    pub fn on_close<F>(&self, f: F)
    where
        F: FnOnce() -> BoxFuture<'static, ()> + Send + 'static,
    {
        self.inner.on_close.register(f);
    }

    fn validate_url(&self) -> String {
        format!(
            "{}{VALIDATE_PATH}",
            self.config().api_url().as_str().trim_end_matches('/')
        )
    }

    async fn validate(&self, operation: &str, ok: &[u16]) -> Result<Response> {
        let t = self.transport();
        let url = self.validate_url();
        let key = self.config().api_key();
        send(t, operation, "GET", &url, || {
            ok.iter().fold(
                t.get(VALIDATE_PATH).secret_header(HEADER_API_KEY, key),
                |req, s| req.ok_status(*s),
            )
        })
        .await
    }

    /// Reachability through `GET /api/v1/validate` (Datadog has no unauthenticated health
    /// endpoint). 2xx → healthy `ok` (`details.apiKeyValid`); 401/403 → not healthy,
    /// `unauthorized`; 429 → `degraded`; other statuses → `unhealthy`; network errors →
    /// `unreachable`. Never fails except for `CANCELLED` and `CONFIG`.
    pub async fn health(&self) -> Result<HealthStatus> {
        let resp = self
            .transport()
            .get(VALIDATE_PATH)
            .secret_header(HEADER_API_KEY, self.config().api_key())
            .operation("health");
        let resp = (400..600).fold(resp, |r, s| r.ok_status(s)).send().await;
        let resp = match resp {
            Ok(resp) => resp,
            Err(err) => return HealthStatus::from_error(err),
        };
        let status = resp.status();
        let hs = match status {
            200..=299 => HealthStatus::healthy("ok").with_detail(
                "apiKeyValid",
                (parse_valid(&resp) == Some(true)).to_string(),
            ),
            401 | 403 => {
                HealthStatus::unhealthy("unauthorized").with_detail("apiKeyValid", "false")
            }
            429 => HealthStatus::unhealthy("degraded"),
            _ => HealthStatus::unhealthy("unhealthy"),
        };
        Ok(hs.with_detail("httpStatus", status.to_string()))
    }

    /// Validates the API key with `GET /api/v1/validate` and returns the latency. A rejected
    /// key (HTTP 401/403) is `AUTHENTICATION`; a 2xx answer without `{"valid": true}` is
    /// `UNAVAILABLE` (unexpected response).
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        let resp = self.validate("ping", &[]).await?;
        if parse_valid(&resp) != Some(true) {
            return Err(unexpected_validate("ping", resp.status()));
        }
        Ok(started.elapsed())
    }

    /// Whether Datadog accepts the configured API key (`GET /api/v1/validate`): `true` for
    /// `{"valid": true}`, `false` for HTTP 403 or `{"valid": false}`. Other failures are
    /// returned as mapped errors; a 2xx answer without a `valid` field is `UNAVAILABLE`.
    pub async fn validate_api_key(&self) -> Result<bool> {
        let resp = self.validate("validateApiKey", &[403]).await?;
        if resp.status() == 403 {
            return Ok(false);
        }
        parse_valid(&resp).ok_or_else(|| unexpected_validate("validateApiKey", resp.status()))
    }

    /// Always `UNSUPPORTED`: Datadog is a SaaS and exposes no server version.
    pub async fn server_version(&self) -> Result<String> {
        Err(Error::unsupported(
            "serverVersion: Datadog exposes no server version",
        ))
    }

    /// The intake exporter (most users call [`log_client`](Self::log_client) instead).
    pub fn exporter(&self) -> DatadogLogExporter {
        DatadogLogExporter::new(self.inner.clone())
    }

    /// Starts a batching [`LogClient`] that sends to the logs intake. The batch limits are
    /// capped at the intake limits; the resource defaults come from the config.
    /// [`close`](Self::close) closes it. Must be called inside a tokio runtime; `CANCELLED`
    /// after `close`.
    pub fn log_client(&self, opts: LogClientOptions) -> Result<DatadogLogClient> {
        if self.is_closed() {
            return Err(Error::cancelled("logClient: client is closed"));
        }
        let mut batch = opts.batch;
        batch.max_batch_size = batch.max_batch_size.min(MAX_ENTRIES_PER_REQUEST);
        batch.max_batch_bytes = batch.max_batch_bytes.min(MAX_PAYLOAD_BYTES);
        let c = self.config();
        let mut resource = Attributes::new();
        for (key, value) in [
            ("service.name", c.service()),
            ("service.version", c.version()),
            ("deployment.environment", c.env()),
            ("host.name", c.hostname()),
        ] {
            if let Some(v) = value {
                resource.insert(key.to_owned(), Value::String(v.to_owned()));
            }
        }
        resource.extend(opts.resource);
        let client = BatchingLogClient::builder(Arc::new(self.exporter()))
            .batch_options(batch)
            .resource(resource)
            .redaction(opts.redaction)
            .min_severity(opts.min_severity)
            .build()?;
        let closing = client.clone();
        self.on_close(move || {
            Box::pin(async move {
                let _ = closing.close().await;
            })
        });
        Ok(client)
    }

    /// The query client (search, aggregate, indexes, tail). `CONFIG` without an application
    /// key.
    pub fn query(&self) -> Result<DatadogLogsQuery> {
        if self.config().app_key().is_none() {
            return Err(Error::config(
                "appKey is required for the query API (GROW_DATADOG_LOGS_APP_KEY or DD_APP_KEY)",
            ));
        }
        Ok(DatadogLogsQuery::new(self.inner.clone()))
    }

    /// Flushes every log client, stops tails, then closes the transport. Idempotent.
    pub async fn close(&self) {
        self.inner.on_close.close().await;
        self.inner.transport.close();
    }

    /// Whether [`DatadogLogsClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.transport.is_closed() || self.inner.on_close.is_closed()
    }
}

fn parse_valid(resp: &Response) -> Option<bool> {
    resp.json::<Value>().ok()?.get("valid")?.as_bool()
}

fn unexpected_validate(operation: &str, status: u16) -> Error {
    Error::unavailable(format!(
        "{operation}: unexpected response from {VALIDATE_PATH} (HTTP {status})"
    ))
    .with_status(status)
    .with_retryable(false)
}
