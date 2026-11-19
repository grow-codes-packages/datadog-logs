//! Datadog query client: Logs Search v2, aggregation, indexes, polling tail (API.md §6;
//! DECISIONS 3, 5, 8).

use std::collections::{HashMap, HashSet};
use std::fmt;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use async_trait::async_trait;
use futures::stream::{self, BoxStream, StreamExt};
use serde::Serialize;
use serde_json::{json, Map, Value};

use crate::client::Inner;
use crate::core::{Error, Method, Result};
use crate::http::{send, HEADER_API_KEY, HEADER_APP_KEY};
use crate::logging::{
    AttrValue, Attributes, LogQueryClient, LogRecord, LogSearchResult, RecordCallback,
    SearchOptions, SortOrder, TailHandle, TailOptions,
};
use crate::mapping::LogEvent;

/// Path of the log search API.
pub const SEARCH_PATH: &str = "/api/v2/logs/events/search";
/// Path of the log aggregation API.
pub const AGGREGATE_PATH: &str = "/api/v2/logs/analytics/aggregate";
/// Path of the log index list.
pub const INDEXES_PATH: &str = "/api/v1/logs/config/indexes";
/// Largest page size of [`DatadogLogsQuery::search_logs`] (Datadog maximum).
pub const MAX_PAGE_LIMIT: usize = 5000;
/// Page size of [`DatadogLogsQuery::search_logs`] when none is given.
pub const DEFAULT_SEARCH_LIMIT: usize = 100;
/// Page size of [`DatadogLogsQuery::iterate_logs`] when none is given.
pub const DEFAULT_ITERATOR_PAGE_SIZE: usize = 1000;
/// Recommended poll interval for `wait_for_log` and `tail` (the search rate limit is low).
pub const DEFAULT_QUERY_POLL_INTERVAL: Duration = Duration::from_secs(5);
/// Default lower time bound of a search (date math).
pub const DEFAULT_SEARCH_FROM: &str = "now-15m";
/// Default upper time bound of a search.
pub const DEFAULT_SEARCH_TO: &str = "now";
/// How far back each tail poll searches again before the newest seen log, so logs indexed late
/// are still delivered (ids are de-duplicated).
pub const TAIL_LAG: Duration = Duration::from_secs(60);

/// Formats a time for `from` / `to`: ISO-8601 UTC with milliseconds.
///
/// ```
/// use std::time::{Duration, UNIX_EPOCH};
/// assert_eq!(grow_datadog_logs::query::format_time(UNIX_EPOCH + Duration::from_millis(1500)),
///            "1970-01-01T00:00:01.500Z");
/// ```
pub fn format_time(t: SystemTime) -> String {
    let t = t.max(SystemTime::UNIX_EPOCH);
    humantime::format_rfc3339_millis(t).to_string()
}

/// Sort order of [`DatadogLogsQuery::search_logs`] (Datadog wire values).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum LogsSort {
    /// Newest first (`-timestamp`, default).
    #[default]
    TimestampDesc,
    /// Oldest first (`timestamp`).
    TimestampAsc,
}

impl LogsSort {
    /// Wire value.
    pub fn as_str(self) -> &'static str {
        match self {
            LogsSort::TimestampDesc => "-timestamp",
            LogsSort::TimestampAsc => "timestamp",
        }
    }
}

/// Input of [`DatadogLogsQuery::search_logs`] and [`DatadogLogsQuery::iterate_logs`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchLogsRequest {
    /// Datadog log search query (default `*`), e.g. `service:checkout "run-7f3a"`.
    pub query: Option<String>,
    /// Lower bound: ISO-8601, epoch milliseconds or date math (default `now-15m`).
    /// See [`format_time`].
    pub from: Option<String>,
    /// Upper bound (default `now`).
    pub to: Option<String>,
    /// Page size of `search_logs` (1..=5000, default 100); total number of logs of
    /// `iterate_logs` (default all).
    pub limit: Option<usize>,
    /// Page size of `iterate_logs` (1..=5000, default min(limit, 1000)).
    pub page_size: Option<usize>,
    /// Sort order (default newest first).
    pub sort: LogsSort,
    /// Indexes to search (default `["*"]`).
    pub indexes: Vec<String>,
    /// `indexes` (default), `online-archives` or `flex`.
    pub storage_tier: Option<String>,
    /// Continues a previous search ([`SearchLogsPage::next_cursor`]).
    pub cursor: Option<String>,
}

impl SearchLogsRequest {
    /// A request for `query` with the defaults.
    pub fn new(query: impl Into<String>) -> Self {
        SearchLogsRequest {
            query: Some(query.into()),
            ..Default::default()
        }
    }
    /// Sets the time range.
    pub fn range(mut self, from: impl Into<String>, to: impl Into<String>) -> Self {
        self.from = Some(from.into());
        self.to = Some(to.into());
        self
    }
    /// Sets the lower bound.
    pub fn from(mut self, from: impl Into<String>) -> Self {
        self.from = Some(from.into());
        self
    }
    /// Sets the limit.
    pub fn limit(mut self, limit: usize) -> Self {
        self.limit = Some(limit);
        self
    }
    /// Sets the iterator page size.
    pub fn page_size(mut self, page_size: usize) -> Self {
        self.page_size = Some(page_size);
        self
    }
    /// Sets the sort order.
    pub fn sort(mut self, sort: LogsSort) -> Self {
        self.sort = sort;
        self
    }
    /// Sets the indexes.
    pub fn indexes<I, S>(mut self, indexes: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.indexes = indexes.into_iter().map(Into::into).collect();
        self
    }
    /// Sets the storage tier.
    pub fn storage_tier(mut self, tier: impl Into<String>) -> Self {
        self.storage_tier = Some(tier.into());
        self
    }
    /// Sets the cursor.
    pub fn cursor(mut self, cursor: impl Into<String>) -> Self {
        self.cursor = Some(cursor.into());
        self
    }
}

/// A partial-result warning of the query API (e.g. code `unknown_index`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct SearchWarning {
    /// Warning code.
    pub code: Option<String>,
    /// Short title.
    pub title: Option<String>,
    /// Details.
    pub detail: Option<String>,
}

/// `meta` of a query response.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct QueryMeta {
    /// Cursor of the next page; `None` on the last page.
    pub next_cursor: Option<String>,
    /// `done`, or `timeout` for partial results.
    pub status: Option<String>,
    /// Partial-result warnings.
    pub warnings: Vec<SearchWarning>,
    /// `meta.request_id`.
    pub request_id: Option<String>,
    /// Server-side duration.
    pub elapsed: Option<Duration>,
}

/// One page of [`DatadogLogsQuery::search_logs`].
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SearchLogsPage {
    /// Events in the requested order.
    pub events: Vec<LogEvent>,
    /// Paging and status information.
    pub meta: QueryMeta,
}

impl SearchLogsPage {
    /// The events as normalized [`LogRecord`]s (see [`LogEvent::to_log_record`]).
    pub fn records(&self) -> Vec<LogRecord> {
        self.events.iter().map(LogEvent::to_log_record).collect()
    }
    /// Cursor of the next page.
    pub fn next_cursor(&self) -> Option<&str> {
        self.meta.next_cursor.as_deref()
    }
}

/// One aggregation of [`DatadogLogsQuery::aggregate_logs`]; results are keyed `c0`, `c1`, …
/// in order.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct Compute {
    /// `count`, `cardinality`, `pc75`, `pc90`, `pc95`, `pc98`, `pc99`, `sum`, `min`, `max`,
    /// `avg` or `median`.
    pub aggregation: String,
    /// `total` (default) or `timeseries`.
    #[serde(rename = "type")]
    pub kind: String,
    /// Timeseries bucket size, e.g. `5m`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub interval: Option<String>,
    /// Measured attribute, e.g. `@duration` (not for `count`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metric: Option<String>,
}

impl Compute {
    /// A `total` compute with `aggregation`.
    pub fn new(aggregation: impl Into<String>) -> Self {
        Compute {
            aggregation: aggregation.into(),
            kind: "total".into(),
            interval: None,
            metric: None,
        }
    }
    /// A `timeseries` compute with `aggregation` per `interval`.
    pub fn timeseries(aggregation: impl Into<String>, interval: impl Into<String>) -> Self {
        Compute {
            kind: "timeseries".into(),
            interval: Some(interval.into()),
            ..Compute::new(aggregation)
        }
    }
    /// Sets the metric.
    pub fn metric(mut self, metric: impl Into<String>) -> Self {
        self.metric = Some(metric.into());
        self
    }
}

/// Orders the buckets of a [`GroupBy`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct GroupBySort {
    /// Aggregation to sort by.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub aggregation: Option<String>,
    /// `asc` or `desc`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub order: Option<String>,
    /// `alphabetical` or `measure`.
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    /// Metric to sort by.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metric: Option<String>,
}

/// Buckets a numeric facet.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct GroupByHistogram {
    /// Bucket width.
    pub interval: f64,
    /// Lower bound.
    pub min: f64,
    /// Upper bound.
    pub max: f64,
}

/// Splits an aggregation by a facet.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct GroupBy {
    /// Facet, e.g. `service` or `@user.id` (required).
    pub facet: String,
    /// Maximum number of buckets (Datadog default 10).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
    /// Bucket order.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sort: Option<GroupBySort>,
    /// Value for logs without the facet.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub missing: Option<AttrValue>,
    /// Adds a total bucket (`true` or its name).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total: Option<AttrValue>,
    /// Numeric histogram.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub histogram: Option<GroupByHistogram>,
}

impl GroupBy {
    /// Groups by `facet`.
    pub fn new(facet: impl Into<String>) -> Self {
        GroupBy {
            facet: facet.into(),
            ..Default::default()
        }
    }
    /// Sets the bucket limit.
    pub fn limit(mut self, limit: u32) -> Self {
        self.limit = Some(limit);
        self
    }
}

/// Filter of [`DatadogLogsQuery::aggregate_logs`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AggregateLogsOptions {
    /// Query (default `*`).
    pub query: Option<String>,
    /// Lower bound (default `now-15m`).
    pub from: Option<String>,
    /// Upper bound (default `now`).
    pub to: Option<String>,
    /// Indexes (default `["*"]`).
    pub indexes: Vec<String>,
    /// Storage tier (default `indexes`).
    pub storage_tier: Option<String>,
    /// Continues a previous aggregation ([`QueryMeta::next_cursor`]).
    pub cursor: Option<String>,
}

impl AggregateLogsOptions {
    /// Options for `query`.
    pub fn new(query: impl Into<String>) -> Self {
        AggregateLogsOptions {
            query: Some(query.into()),
            ..Default::default()
        }
    }
    /// Sets the time range.
    pub fn range(mut self, from: impl Into<String>, to: impl Into<String>) -> Self {
        self.from = Some(from.into());
        self.to = Some(to.into());
        self
    }
}

/// One point of a timeseries compute.
#[derive(Debug, Clone, PartialEq)]
pub struct TimeseriesPoint {
    /// Bucket start.
    pub time: Option<SystemTime>,
    /// Value.
    pub value: f64,
}

/// One group of an aggregation.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct AggregateBucket {
    /// Facet → bucket value (empty without group-by).
    pub by: Attributes,
    /// `c0`, `c1`, … → number, string or timeseries array of `{time, value}`.
    pub computes: Attributes,
}

impl AggregateBucket {
    /// Compute `key` (e.g. `c0`) as a number.
    pub fn number(&self, key: &str) -> Option<f64> {
        match self.computes.get(key)? {
            Value::Number(n) => n.as_f64(),
            Value::String(s) => s.trim().parse().ok(),
            _ => None,
        }
    }
    /// Compute `key` as timeseries points (`None` when it is not one).
    pub fn timeseries(&self, key: &str) -> Option<Vec<TimeseriesPoint>> {
        let items = self.computes.get(key)?.as_array()?;
        Some(
            items
                .iter()
                .filter_map(|p| {
                    Some(TimeseriesPoint {
                        time: p
                            .get("time")
                            .and_then(Value::as_str)
                            .and_then(crate::mapping::parse_rfc3339),
                        value: p.get("value")?.as_f64()?,
                    })
                })
                .collect(),
        )
    }
}

/// Answer of [`DatadogLogsQuery::aggregate_logs`].
#[derive(Debug, Clone, Default, PartialEq)]
pub struct AggregateLogsResult {
    /// Buckets.
    pub buckets: Vec<AggregateBucket>,
    /// Paging and status information.
    pub meta: QueryMeta,
}

/// One Datadog log index.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LogIndex {
    /// Index name.
    pub name: String,
    /// Index filter query.
    pub query: Option<String>,
    /// Retention in days.
    pub retention_days: Option<i64>,
    /// Daily quota.
    pub daily_limit: Option<i64>,
    /// Whether the index hit its quota.
    pub is_rate_limited: Option<bool>,
}

/// Datadog Logs query client (needs the application key): `search_logs` (one page),
/// `iterate_logs` (cursor pagination), `aggregate_logs`, `list_indexes`, and the category
/// [`LogQueryClient`] methods: `search`, `wait_for_log`, `tail` (polling search),
/// `list_streams` (index names, needs `logs_read_config`); `list_labels` is `UNSUPPORTED`.
/// Get it from [`DatadogLogsClient::query`](crate::DatadogLogsClient::query). Cheap to clone.
#[derive(Clone)]
pub struct DatadogLogsQuery {
    inner: Arc<Inner>,
}

impl fmt::Debug for DatadogLogsQuery {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DatadogLogsQuery").finish_non_exhaustive()
    }
}

fn unexpected(operation: &str, what: &str) -> Error {
    Error::unavailable(format!("{operation}: unexpected response ({what})")).with_retryable(false)
}

fn filter_body(
    query: Option<&str>,
    from: Option<&str>,
    to: Option<&str>,
    indexes: &[String],
    tier: Option<&str>,
) -> Value {
    let pick = |v: Option<&str>, d: &str| {
        v.map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(d)
            .to_owned()
    };
    json!({
        "query": pick(query, "*"),
        "from": pick(from, DEFAULT_SEARCH_FROM),
        "to": pick(to, DEFAULT_SEARCH_TO),
        "indexes": if indexes.is_empty() { vec!["*".to_owned()] } else { indexes.to_vec() },
        "storage_tier": pick(tier, "indexes"),
    })
}

fn parse_meta(body: &Map<String, Value>) -> QueryMeta {
    let empty = Map::new();
    let meta = body
        .get("meta")
        .and_then(Value::as_object)
        .unwrap_or(&empty);
    let s = |v: Option<&Value>| v.and_then(Value::as_str).map(str::to_owned);
    QueryMeta {
        next_cursor: s(meta.get("page").and_then(|p| p.get("after"))).filter(|c| !c.is_empty()),
        status: s(meta.get("status")),
        warnings: meta
            .get("warnings")
            .and_then(Value::as_array)
            .map(|ws| {
                ws.iter()
                    .filter(|w| w.is_object())
                    .map(|w| SearchWarning {
                        code: s(w.get("code")),
                        title: s(w.get("title")),
                        detail: s(w.get("detail")),
                    })
                    .collect()
            })
            .unwrap_or_default(),
        request_id: s(meta.get("request_id")),
        elapsed: meta
            .get("elapsed")
            .and_then(Value::as_u64)
            .map(Duration::from_millis),
    }
}

fn check_limit(value: Option<usize>, operation: &str, name: &str) -> Result<()> {
    match value {
        Some(n) if n == 0 || n > MAX_PAGE_LIMIT => Err(Error::validation(format!(
            "{operation}: {name} must be an integer 1..{MAX_PAGE_LIMIT}"
        ))),
        _ => Ok(()),
    }
}

fn to_attributes(value: Option<&Value>) -> Attributes {
    value
        .and_then(Value::as_object)
        .map(|m| m.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
        .unwrap_or_default()
}

impl DatadogLogsQuery {
    pub(crate) fn new(inner: Arc<Inner>) -> Self {
        DatadogLogsQuery { inner }
    }

    async fn call(
        &self,
        operation: &str,
        method: Method,
        path: &str,
        body: Option<&Value>,
    ) -> Result<Map<String, Value>> {
        let t = &self.inner.transport;
        let api_key = self.inner.config.api_key();
        let app_key = self.inner.config.app_key();
        let url = format!(
            "{}{path}",
            self.inner.config.api_url().as_str().trim_end_matches('/')
        );
        let resp = send(t, operation, method.as_str(), &url, || {
            let mut req = t
                .request(method.clone(), path)
                .secret_header(HEADER_API_KEY, api_key)
                .idempotent(true);
            if let Some(key) = app_key {
                req = req.secret_header(HEADER_APP_KEY, key);
            }
            match body {
                Some(b) => req.json(b),
                None => req,
            }
        })
        .await?;
        match resp.json::<Value>() {
            Ok(Value::Object(map)) => Ok(map),
            Ok(_) => Err(unexpected(operation, "no JSON object").with_status(resp.status())),
            Err(_) => Err(unexpected(operation, "invalid JSON").with_status(resp.status())),
        }
    }

    /// One page of `POST /api/v2/logs/events/search`. Pass `next_cursor` back as `cursor` (same
    /// filter and sort) for the next page. A `timeout` status and warnings mean partial results
    /// and are returned, not raised. A limit outside 1..=5000 is `VALIDATION`; a 200 answer
    /// without a `data` array is `UNAVAILABLE`.
    pub async fn search_logs(&self, req: &SearchLogsRequest) -> Result<SearchLogsPage> {
        let op = "logs.searchLogs";
        check_limit(req.limit, op, "limit")?;
        let mut page = json!({ "limit": req.limit.unwrap_or(DEFAULT_SEARCH_LIMIT) });
        if let Some(cursor) = req.cursor.as_deref().filter(|c| !c.is_empty()) {
            page["cursor"] = cursor.into();
        }
        let body = json!({
            "filter": filter_body(req.query.as_deref(), req.from.as_deref(), req.to.as_deref(),
                &req.indexes, req.storage_tier.as_deref()),
            "sort": req.sort.as_str(),
            "page": page,
        });
        let resp = self
            .call(op, Method::POST, SEARCH_PATH, Some(&body))
            .await?;
        let data = resp
            .get("data")
            .and_then(Value::as_array)
            .ok_or_else(|| unexpected(op, "no data array").with_status(200))?;
        Ok(SearchLogsPage {
            events: data.iter().filter_map(LogEvent::from_json).collect(),
            meta: parse_meta(&resp),
        })
    }

    /// Every matching event across pages (cursor pagination), as a stream. `limit` caps the
    /// total number of events (default all); `page_size` sets the page size. The stream ends
    /// after the first error, which it yields once: a zero limit or a page size outside
    /// 1..=5000 is `VALIDATION`, a repeated cursor `UNAVAILABLE` (protocol loop guard).
    pub fn iterate_logs(&self, req: SearchLogsRequest) -> BoxStream<'static, Result<LogEvent>> {
        struct State {
            query: DatadogLogsQuery,
            req: SearchLogsRequest,
            buffer: std::vec::IntoIter<LogEvent>,
            remaining: usize,
            page_size: usize,
            seen: HashSet<String>,
            pending: Option<Error>,
            done: bool,
        }
        let op = "logs.iterateLogs";
        let invalid = if req.limit == Some(0) {
            Some(Error::validation(format!(
                "{op}: limit must be a positive integer"
            )))
        } else {
            check_limit(req.page_size, op, "pageSize").err()
        };
        if let Some(err) = invalid {
            return stream::once(async move { Err(err) }).boxed();
        }
        let remaining = req.limit.unwrap_or(usize::MAX);
        let page_size = req
            .page_size
            .unwrap_or_else(|| remaining.min(DEFAULT_ITERATOR_PAGE_SIZE));
        let state = State {
            query: self.clone(),
            req,
            buffer: Vec::new().into_iter(),
            remaining,
            page_size,
            seen: HashSet::new(),
            pending: None,
            done: false,
        };
        stream::unfold(state, move |mut st| async move {
            loop {
                if st.remaining == 0 {
                    return None;
                }
                if let Some(event) = st.buffer.next() {
                    st.remaining -= 1;
                    return Some((Ok(event), st));
                }
                if let Some(err) = st.pending.take() {
                    st.remaining = 0;
                    return Some((Err(err), st));
                }
                if st.done {
                    return None;
                }
                let mut req = st.req.clone();
                req.limit = Some(st.page_size.min(st.remaining));
                let page = match st.query.search_logs(&req).await {
                    Ok(page) => page,
                    Err(err) => {
                        st.done = true;
                        st.remaining = 0;
                        return Some((Err(err), st));
                    }
                };
                st.done = true;
                if let Some(next) = page.meta.next_cursor.filter(|_| !page.events.is_empty()) {
                    if st.seen.insert(next.clone()) {
                        st.req.cursor = Some(next);
                        st.done = false;
                    } else {
                        st.pending = Some(unexpected(op, "the service repeated a page cursor"));
                    }
                }
                st.buffer = page.events.into_iter();
            }
        })
        .boxed()
    }

    /// `POST /api/v2/logs/analytics/aggregate`: computes `compute` (results keyed `c0`, `c1`, …)
    /// over the logs matching `opts`, optionally grouped by facets. An empty compute list, an
    /// empty aggregation or an empty facet is `VALIDATION`.
    pub async fn aggregate_logs(
        &self,
        compute: &[Compute],
        group_by: &[GroupBy],
        opts: &AggregateLogsOptions,
    ) -> Result<AggregateLogsResult> {
        let op = "logs.aggregateLogs";
        if compute.is_empty() {
            return Err(Error::validation(format!(
                "{op}: compute must not be empty"
            )));
        }
        if compute.iter().any(|c| c.aggregation.trim().is_empty()) {
            return Err(Error::validation(format!(
                "{op}: compute.aggregation is required"
            )));
        }
        if group_by.iter().any(|g| g.facet.trim().is_empty()) {
            return Err(Error::validation(format!(
                "{op}: groupBy.facet is required"
            )));
        }
        let computes: Vec<Value> = compute
            .iter()
            .map(|c| {
                let mut v = serde_json::to_value(c).unwrap_or_default();
                if c.kind.trim().is_empty() {
                    v["type"] = "total".into();
                }
                v
            })
            .collect();
        let mut body = json!({
            "compute": computes,
            "filter": filter_body(opts.query.as_deref(), opts.from.as_deref(), opts.to.as_deref(),
                &opts.indexes, opts.storage_tier.as_deref()),
        });
        if !group_by.is_empty() {
            body["group_by"] = serde_json::to_value(group_by).unwrap_or_default();
        }
        if let Some(cursor) = opts.cursor.as_deref().filter(|c| !c.is_empty()) {
            body["page"] = json!({ "cursor": cursor });
        }
        let resp = self
            .call(op, Method::POST, AGGREGATE_PATH, Some(&body))
            .await?;
        let buckets = resp
            .get("data")
            .and_then(|d| d.get("buckets"))
            .and_then(Value::as_array)
            .ok_or_else(|| unexpected(op, "no data.buckets").with_status(200))?;
        Ok(AggregateLogsResult {
            buckets: buckets
                .iter()
                .filter(|b| b.is_object())
                .map(|b| AggregateBucket {
                    by: to_attributes(b.get("by")),
                    computes: to_attributes(b.get("computes")),
                })
                .collect(),
            meta: parse_meta(&resp),
        })
    }

    /// Log indexes from `GET /api/v1/logs/config/indexes` (needs `logs_read_config`).
    pub async fn list_indexes(&self) -> Result<Vec<LogIndex>> {
        let op = "logs.listIndexes";
        let resp = self.call(op, Method::GET, INDEXES_PATH, None).await?;
        let indexes = resp
            .get("indexes")
            .and_then(Value::as_array)
            .ok_or_else(|| unexpected(op, "no indexes array").with_status(200))?;
        Ok(indexes
            .iter()
            .filter_map(|i| {
                Some(LogIndex {
                    name: i.get("name")?.as_str()?.to_owned(),
                    query: i
                        .get("filter")
                        .and_then(|f| f.get("query"))
                        .and_then(Value::as_str)
                        .map(str::to_owned),
                    retention_days: i.get("num_retention_days").and_then(Value::as_i64),
                    daily_limit: i.get("daily_limit").and_then(Value::as_i64),
                    is_rate_limited: i.get("is_rate_limited").and_then(Value::as_bool),
                })
            })
            .collect())
    }

    async fn tail_loop(
        self,
        query: String,
        on_record: RecordCallback,
        start: SystemTime,
        interval: Duration,
        handle: TailHandle,
    ) {
        let mut seen: HashMap<String, SystemTime> = HashMap::new();
        let mut newest = start;
        while !handle.is_stopped() {
            let from = newest.checked_sub(TAIL_LAG).unwrap_or(start).max(start);
            let req = SearchLogsRequest::new(query.clone())
                .from(format_time(from))
                .sort(LogsSort::TimestampAsc);
            let mut events = self.iterate_logs(req);
            loop {
                let next = tokio::select! {
                    _ = handle.stopped() => return,
                    next = events.next() => next,
                };
                match next {
                    None => break,
                    Some(Err(err)) if err.is_retryable() => {
                        tracing::debug!(target: "grow_datadog_logs", code = %err.code(), "logs.tail: retryable search error");
                        break;
                    }
                    Some(Err(err)) => {
                        tracing::warn!(target: "grow_datadog_logs", code = %err.code(), error = %err, "logs.tail: stopped");
                        handle.stop();
                        return;
                    }
                    Some(Ok(event)) => {
                        let ts = event.timestamp.unwrap_or(start);
                        if seen.insert(event.id.clone(), ts).is_some() {
                            continue;
                        }
                        newest = newest.max(ts);
                        let record = event.to_log_record();
                        let cb = on_record.clone();
                        let _ =
                            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cb(record)));
                    }
                }
            }
            let horizon = newest.checked_sub(TAIL_LAG * 2).unwrap_or(start);
            seen.retain(|_, ts| *ts >= horizon);
            tokio::select! {
                _ = handle.stopped() => return,
                _ = tokio::time::sleep(interval) => {}
            }
        }
    }
}

#[async_trait]
impl LogQueryClient for DatadogLogsQuery {
    /// One page of [`search_logs`](DatadogLogsQuery::search_logs): `start`/`end` → `from`/`to`
    /// (default the last 15 minutes), `order` → `sort`, `cursor` → `page.cursor`, `limit`
    /// (at most 5000). `total` is never set (Datadog returns none).
    async fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        let req = SearchLogsRequest {
            query: Some(query.to_owned()),
            from: opts.start.map(format_time),
            to: opts.end.map(format_time),
            limit: Some(opts.limit),
            sort: match opts.order {
                SortOrder::Asc => LogsSort::TimestampAsc,
                SortOrder::Desc => LogsSort::TimestampDesc,
            },
            cursor: opts.cursor,
            ..Default::default()
        };
        let page = self.search_logs(&req).await?;
        Ok(LogSearchResult {
            records: page.records(),
            next_cursor: page.meta.next_cursor,
            total: None,
        })
    }

    /// Polling tail over the search API (Datadog Live Tail has no public API): every
    /// `opts.interval` it searches from `opts.start` (default now), oldest first, and delivers
    /// each log id once. Retryable errors keep polling; other errors stop the tail. Mind the
    /// search rate limit ([`DEFAULT_QUERY_POLL_INTERVAL`] is recommended).
    /// [`DatadogLogsClient::close`](crate::DatadogLogsClient::close) stops it.
    async fn tail(
        &self,
        query: &str,
        on_record: RecordCallback,
        opts: TailOptions,
    ) -> Result<TailHandle> {
        if self.inner.on_close.is_closed() {
            return Err(Error::cancelled("logs.tail: client is closed"));
        }
        let handle = TailHandle::new();
        let stop = handle.clone();
        self.inner.on_close.register(move || {
            Box::pin(async move {
                stop.stop();
            })
        });
        let interval = if opts.interval.is_zero() {
            DEFAULT_QUERY_POLL_INTERVAL
        } else {
            opts.interval
        };
        let start = opts.start.unwrap_or_else(SystemTime::now);
        tokio::spawn(self.clone().tail_loop(
            query.to_owned(),
            on_record,
            start,
            interval,
            handle.clone(),
        ));
        Ok(handle)
    }

    /// Index names from `GET /api/v1/logs/config/indexes` (needs `logs_read_config`).
    async fn list_streams(&self) -> Result<Vec<String>> {
        Ok(self
            .list_indexes()
            .await?
            .into_iter()
            .map(|i| i.name)
            .collect())
    }

    /// Always `UNSUPPORTED`: Datadog has no public API that lists log facets or attributes.
    async fn list_labels(&self) -> Result<Vec<String>> {
        Err(Error::unsupported(
            "logs.listLabels: Datadog has no API that lists log attributes",
        ))
    }
}
