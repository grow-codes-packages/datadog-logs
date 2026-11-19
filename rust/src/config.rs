// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `DatadogLogsConfig`: common settings (SPEC §2.2) plus the Datadog credentials, the site
//! resolver and the reserved-attribute defaults (API.md §1–§2, DECISIONS 1 and 9).

use std::fmt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use url::Url;

use crate::core::redact::redact_url;
use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Error, Hooks, Result, RetryPolicy, Secret,
    TlsConfig,
};

/// Datadog site used when none is configured (US1).
pub const DEFAULT_SITE: &str = "datadoghq.com";

/// `ddsource` sent with every log when none is configured (DECISIONS 9).
pub const DEFAULT_SOURCE: &str = "grow-sdk";

/// Default API base URL (the API host of [`DEFAULT_SITE`]).
pub const DEFAULT_URL: &str = "https://api.datadoghq.com";

/// Default logs intake base URL (the intake host of [`DEFAULT_SITE`]).
pub const DEFAULT_INTAKE_URL: &str = "https://http-intake.logs.datadoghq.com";

/// Datadog sites accepted by [`DatadogLogsConfigBuilder::site`] and [`resolve_site`] (the
/// `servers` enum of the official OpenAPI specification).
pub const SITES: &[&str] = &[
    "datadoghq.com",
    "us3.datadoghq.com",
    "us5.datadoghq.com",
    "ap1.datadoghq.com",
    "ap2.datadoghq.com",
    "uk1.datadoghq.com",
    "datadoghq.eu",
    "ddog-gov.com",
    "us2.ddog-gov.com",
];

const VENDOR_API_KEY: &[&str] = &["DD_API_KEY"];
const VENDOR_APP_KEY: &[&str] = &["DD_APP_KEY", "DD_APPLICATION_KEY"];
const VENDOR_SITE: &[&str] = &["DD_SITE"];
const VENDOR_SERVICE: &[&str] = &["DD_SERVICE"];
const VENDOR_ENV: &[&str] = &["DD_ENV"];
const VENDOR_VERSION: &[&str] = &["DD_VERSION"];
const VENDOR_TAGS: &[&str] = &["DD_TAGS"];
const VENDOR_HOSTNAME: &[&str] = &["DD_HOSTNAME"];

/// The two base URLs of a Datadog site.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SiteEndpoints {
    /// Logs intake base, for example `https://http-intake.logs.datadoghq.com`.
    pub intake_url: String,
    /// API base, for example `https://api.datadoghq.com`.
    pub api_url: String,
}

/// Returns the intake and API base URLs of a Datadog site (`datadoghq.com`, `datadoghq.eu`,
/// `us3.datadoghq.com`, …). The site is the API site, never the UI host
/// (`app.datadoghq.com`). It is trimmed and lower-cased; an empty site means [`DEFAULT_SITE`].
/// Unknown sites are a `CONFIG` error.
///
/// ```
/// let ep = grow_datadog_logs::resolve_site("datadoghq.eu").unwrap();
/// assert_eq!(ep.intake_url, "https://http-intake.logs.datadoghq.eu");
/// assert_eq!(ep.api_url, "https://api.datadoghq.eu");
/// ```
pub fn resolve_site(site: &str) -> Result<SiteEndpoints> {
    let mut s = site.trim().to_ascii_lowercase();
    if s.is_empty() {
        s = DEFAULT_SITE.to_owned();
    }
    if !SITES.contains(&s.as_str()) {
        return Err(Error::config(format!(
            "site is not a known Datadog site (use one of {})",
            SITES.join(", ")
        )));
    }
    Ok(SiteEndpoints {
        intake_url: format!("https://http-intake.logs.{s}"),
        api_url: format!("https://api.{s}"),
    })
}

/// Splits a `DD_TAGS` style list: `key:value` tags separated by commas and/or whitespace.
/// Empty items are dropped.
///
/// ```
/// assert_eq!(grow_datadog_logs::parse_tags("team:sdk, lang:rust env:dev"),
///            vec!["team:sdk", "lang:rust", "env:dev"]);
/// ```
pub fn parse_tags(value: &str) -> Vec<String> {
    value
        .split(|c: char| c == ',' || c.is_whitespace())
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
        .collect()
}

/// Immutable client configuration; build it with [`DatadogLogsConfig::builder`] or
/// [`DatadogLogsConfig::from_env`]. `Debug` output redacts every credential.
///
/// Requests go to two hosts derived from the site: the logs intake and the API.
/// [`DatadogLogsConfigBuilder::url`] sets both at once (tests, proxies, fakeintake);
/// [`intake_url`](DatadogLogsConfigBuilder::intake_url) and
/// [`api_url`](DatadogLogsConfigBuilder::api_url) override one each.
#[derive(Clone)]
pub struct DatadogLogsConfig {
    common: CommonConfig,
    intake_url: Url,
    site: String,
    api_key: Secret,
    app_key: Option<Secret>,
    service: Option<String>,
    env: Option<String>,
    version: Option<String>,
    tags: Vec<String>,
    hostname: Option<String>,
    source: String,
}

impl fmt::Debug for DatadogLogsConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DatadogLogsConfig")
            .field("common", &self.common)
            .field("intake_url", &redact_url(self.intake_url.as_str()))
            .field("site", &self.site)
            .field("api_key", &self.api_key)
            .field("app_key", &self.app_key)
            .field("service", &self.service)
            .field("env", &self.env)
            .field("version", &self.version)
            .field("tags", &self.tags)
            .field("hostname", &self.hostname)
            .field("source", &self.source)
            .finish()
    }
}

impl DatadogLogsConfig {
    /// A builder with nothing set.
    pub fn builder() -> DatadogLogsConfigBuilder {
        DatadogLogsConfigBuilder::default()
    }

    /// Configuration from the process environment
    /// (explicit > `GROW_DATADOG_LOGS_*` > `DD_*` vendor variables > defaults).
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK; its URL is the API base.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// API base URL (same as [`DatadogLogsConfig::api_url`]).
    pub fn url(&self) -> &Url {
        self.common.url()
    }

    /// API base URL (validate, search, aggregate, indexes).
    pub fn api_url(&self) -> &Url {
        self.common.url()
    }

    /// Logs intake base URL (`POST /api/v2/logs`).
    pub fn intake_url(&self) -> &Url {
        &self.intake_url
    }

    /// The Datadog site (lower-case; [`DEFAULT_SITE`] when none was set).
    pub fn site(&self) -> &str {
        &self.site
    }

    /// The API key (header `DD-API-KEY`).
    pub fn api_key(&self) -> &Secret {
        &self.api_key
    }

    /// The application key (header `DD-APPLICATION-KEY`, query API only).
    pub fn app_key(&self) -> Option<&Secret> {
        self.app_key.as_ref()
    }

    /// Reserved `service` used when a record has no `service.name` resource.
    pub fn service(&self) -> Option<&str> {
        self.service.as_deref()
    }

    /// Environment: adds `env:<env>` when a record has no `deployment.environment` resource.
    pub fn env(&self) -> Option<&str> {
        self.env.as_deref()
    }

    /// Version: adds `version:<version>` when a record has no `service.version` resource.
    pub fn version(&self) -> Option<&str> {
        self.version.as_deref()
    }

    /// Tags added to every log's `ddtags`.
    pub fn tags(&self) -> &[String] {
        &self.tags
    }

    /// Reserved `hostname` used when a record has no `host.name` resource.
    pub fn hostname(&self) -> Option<&str> {
        self.hostname.as_deref()
    }

    /// `ddsource` of every log (default [`DEFAULT_SOURCE`]).
    pub fn source(&self) -> &str {
        &self.source
    }

    /// The effective intake and API base URLs (without trailing slash).
    pub fn endpoints(&self) -> SiteEndpoints {
        SiteEndpoints {
            intake_url: self.intake_url.as_str().trim_end_matches('/').to_owned(),
            api_url: self.api_url().as_str().trim_end_matches('/').to_owned(),
        }
    }

    /// Checks the service rules (they are also checked when building): an API key is set.
    pub fn validate(&self) -> Result<()> {
        if self.api_key.is_empty() {
            return Err(missing_api_key());
        }
        Ok(())
    }
}

fn missing_api_key() -> Error {
    Error::config("apiKey is required (GROW_DATADOG_LOGS_API_KEY or DD_API_KEY)")
}

fn non_blank(value: Option<String>) -> Option<String> {
    value.map(|v| v.trim().to_owned()).filter(|v| !v.is_empty())
}

/// Builder for [`DatadogLogsConfig`]. Values set here win over the environment.
#[derive(Debug, Clone, Default)]
pub struct DatadogLogsConfigBuilder {
    common: CommonConfigBuilder,
    url: Option<String>,
    intake_url: Option<String>,
    api_url: Option<String>,
    site: Option<String>,
    api_key: Option<Secret>,
    app_key: Option<Secret>,
    service: Option<String>,
    env: Option<String>,
    version: Option<String>,
    tags: Option<Vec<String>>,
    hostname: Option<String>,
    source: Option<String>,
}

impl DatadogLogsConfigBuilder {
    /// Base URL for both the intake and the API (env `GROW_DATADOG_LOGS_URL`); default:
    /// derived from the site.
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.url = Some(url.into());
        self
    }
    /// Logs intake base URL (env `GROW_DATADOG_LOGS_INTAKE_URL`).
    pub fn intake_url(mut self, url: impl Into<String>) -> Self {
        self.intake_url = Some(url.into());
        self
    }
    /// API base URL (env `GROW_DATADOG_LOGS_API_URL`).
    pub fn api_url(mut self, url: impl Into<String>) -> Self {
        self.api_url = Some(url.into());
        self
    }
    /// Datadog site, see [`SITES`] (env `GROW_DATADOG_LOGS_SITE`, then `DD_SITE`).
    pub fn site(mut self, site: impl Into<String>) -> Self {
        self.site = Some(site.into());
        self
    }
    /// API key, required (env `GROW_DATADOG_LOGS_API_KEY`, then `DD_API_KEY`).
    pub fn api_key(mut self, key: impl Into<Secret>) -> Self {
        self.api_key = Some(key.into());
        self
    }
    /// Application key for the query API (env `GROW_DATADOG_LOGS_APP_KEY`, then `DD_APP_KEY`,
    /// `DD_APPLICATION_KEY`). Never sent to the intake.
    pub fn app_key(mut self, key: impl Into<Secret>) -> Self {
        self.app_key = Some(key.into());
        self
    }
    /// Reserved `service` (env `GROW_DATADOG_LOGS_SERVICE`, then `DD_SERVICE`).
    pub fn service(mut self, service: impl Into<String>) -> Self {
        self.service = Some(service.into());
        self
    }
    /// Environment tag (env `GROW_DATADOG_LOGS_ENV`, then `DD_ENV`).
    pub fn env(mut self, env: impl Into<String>) -> Self {
        self.env = Some(env.into());
        self
    }
    /// Version tag (env `GROW_DATADOG_LOGS_VERSION`, then `DD_VERSION`).
    pub fn version(mut self, version: impl Into<String>) -> Self {
        self.version = Some(version.into());
        self
    }
    /// Tags added to every log (env `GROW_DATADOG_LOGS_TAGS`, then `DD_TAGS`).
    pub fn tags<I, S>(mut self, tags: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.tags = Some(tags.into_iter().map(Into::into).collect());
        self
    }
    /// Reserved `hostname` (env `GROW_DATADOG_LOGS_HOSTNAME`, then `DD_HOSTNAME`).
    pub fn hostname(mut self, hostname: impl Into<String>) -> Self {
        self.hostname = Some(hostname.into());
        self
    }
    /// `ddsource` (default [`DEFAULT_SOURCE`]; env `GROW_DATADOG_LOGS_SOURCE`).
    pub fn source(mut self, source: impl Into<String>) -> Self {
        self.source = Some(source.into());
        self
    }
    /// Connect timeout (default 10 s).
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.connect_timeout(timeout);
        self
    }
    /// Per-attempt timeout (default 30 s).
    pub fn request_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.request_timeout(timeout);
        self
    }
    /// Whole-call timeout including retries (default 120 s).
    pub fn total_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.total_timeout(timeout);
        self
    }
    /// Retry policy.
    pub fn retry(mut self, retry: RetryPolicy) -> Self {
        self.common = self.common.retry(retry);
        self
    }
    /// Maximum retries (default 3).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.common = self.common.max_retries(max_retries);
        self
    }
    /// TLS settings.
    pub fn tls(mut self, tls: TlsConfig) -> Self {
        self.common = self.common.tls(tls);
        self
    }
    /// Extra trusted CA certificates (PEM file).
    pub fn ca_cert_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.common = self.common.ca_cert_path(path);
        self
    }
    /// Disables TLS verification (tests only).
    pub fn insecure_skip_verify(mut self, skip: bool) -> Self {
        self.common = self.common.insecure_skip_verify(skip);
        self
    }
    /// Proxy URL (environment proxies are ignored).
    pub fn proxy(mut self, proxy: impl Into<String>) -> Self {
        self.common = self.common.proxy(proxy);
        self
    }
    /// Extra request header.
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.common = self.common.header(name, value);
        self
    }
    /// Text appended to the User-Agent.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.common = self.common.user_agent_suffix(suffix);
        self
    }
    /// Request hooks.
    pub fn hooks(mut self, hooks: Arc<dyn Hooks>) -> Self {
        self.common = self.common.hooks(hooks);
        self
    }
    /// Edits any other common setting. The URL is always replaced by the API base URL.
    pub fn common(mut self, f: impl FnOnce(CommonConfigBuilder) -> CommonConfigBuilder) -> Self {
        self.common = f(self.common);
        self
    }

    /// Builds without reading the environment. `CONFIG` errors: unknown site (unless both
    /// URLs are set), invalid URLs, missing API key, invalid common settings.
    pub fn build(self) -> Result<DatadogLogsConfig> {
        let url = non_blank(self.url);
        let mut intake = non_blank(self.intake_url).or_else(|| url.clone());
        let mut api = non_blank(self.api_url).or(url);
        let site = non_blank(self.site)
            .map(|s| s.to_ascii_lowercase())
            .unwrap_or_else(|| DEFAULT_SITE.to_owned());
        if intake.is_none() || api.is_none() {
            let ep = resolve_site(&site)?;
            intake.get_or_insert(ep.intake_url);
            api.get_or_insert(ep.api_url);
        }
        let (intake, api) = (intake.unwrap_or_default(), api.unwrap_or_default());
        let intake_url = Url::parse(&intake)
            .map_err(|_| Error::config("intake url is not a valid absolute URL"))?;
        if !matches!(intake_url.scheme(), "http" | "https") {
            return Err(Error::config("intake url scheme must be http or https"));
        }
        let common = self.common.url(api).build(DEFAULT_URL)?;
        let api_key = self.api_key.filter(|k| !k.is_empty());
        let config = DatadogLogsConfig {
            common,
            intake_url,
            site,
            api_key: api_key.ok_or_else(missing_api_key)?,
            app_key: self.app_key.filter(|k| !k.is_empty()),
            service: non_blank(self.service),
            env: non_blank(self.env),
            version: non_blank(self.version),
            tags: self
                .tags
                .unwrap_or_default()
                .into_iter()
                .map(|t| t.trim().to_owned())
                .filter(|t| !t.is_empty())
                .collect(),
            hostname: non_blank(self.hostname),
            source: non_blank(self.source).unwrap_or_else(|| DEFAULT_SOURCE.to_owned()),
        };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<DatadogLogsConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env` (`GROW_DATADOG_LOGS_*`, then the `DD_*` variables), then
    /// builds.
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<DatadogLogsConfig> {
        if self.url.is_none() {
            self.url = env.string("URL", &[]);
        }
        self.common = self.common.apply_env(env, &[])?;
        let fill = |slot: &mut Option<String>, name: &str, vendor: &[&str]| {
            if slot.is_none() {
                *slot = env.string(name, vendor);
            }
        };
        fill(&mut self.intake_url, "INTAKE_URL", &[]);
        fill(&mut self.api_url, "API_URL", &[]);
        fill(&mut self.site, "SITE", VENDOR_SITE);
        fill(&mut self.service, "SERVICE", VENDOR_SERVICE);
        fill(&mut self.env, "ENV", VENDOR_ENV);
        fill(&mut self.version, "VERSION", VENDOR_VERSION);
        fill(&mut self.hostname, "HOSTNAME", VENDOR_HOSTNAME);
        fill(&mut self.source, "SOURCE", &[]);
        if self.api_key.is_none() {
            self.api_key = env.secret("API_KEY", VENDOR_API_KEY);
        }
        if self.app_key.is_none() {
            self.app_key = env.secret("APP_KEY", VENDOR_APP_KEY);
        }
        if self.tags.is_none() {
            self.tags = env.string("TAGS", VENDOR_TAGS).map(|t| parse_tags(&t));
        }
        self.build()
    }
}
