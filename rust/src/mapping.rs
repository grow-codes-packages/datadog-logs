//! `LogRecord` ⇄ Datadog JSON: intake entries, limits, trace-id conversion and search-event
//! normalization (API.md §5.2, §6.1; DECISIONS 6, 7, 9).

use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Map, Value};

use crate::logging::{AttrValue, Attributes, LogRecord, Severity};

/// Maximum entries in one intake request (Datadog limit).
pub const MAX_ENTRIES_PER_REQUEST: usize = 1000;
/// Maximum uncompressed intake payload in bytes, brackets and commas included (Datadog: 5 MB).
pub const MAX_PAYLOAD_BYTES: usize = 5_000_000;
/// Maximum serialized size of one log entry in bytes (Datadog: 1 MB).
pub const MAX_LOG_BYTES: usize = 1_000_000;
/// Attribute set to `true` on an entry whose message was truncated by the SDK.
pub const TRUNCATED_ATTRIBUTE: &str = "grow.truncated";
/// Prefix given to user attributes whose name is reserved (DECISIONS 7).
pub const RESERVED_PREFIX: &str = "attr.";

/// Top-level keys the SDK writes itself. A user attribute with one of these names is sent as
/// `attr.<name>` instead.
pub const RESERVED_KEYS: &[&str] = &[
    "message",
    "timestamp",
    "status",
    "service",
    "hostname",
    "ddsource",
    "ddtags",
    "dd.trace_id",
    "dd.span_id",
    "otel.trace_id",
    "otel.span_id",
    TRUNCATED_ATTRIBUTE,
];

/// Defaults applied when a record does not carry the value in its resource.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct EntryDefaults {
    /// Reserved `service` when the record has no `service.name` resource.
    pub service: Option<String>,
    /// Reserved `hostname` when the record has no `host.name` resource.
    pub hostname: Option<String>,
    /// `env:` tag when the record has no `deployment.environment` resource.
    pub env: Option<String>,
    /// `version:` tag when the record has no `service.version` resource.
    pub version: Option<String>,
    /// Extra `ddtags`.
    pub tags: Vec<String>,
    /// `ddsource` (omitted when `None`).
    pub source: Option<String>,
}

fn is_hex(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Datadog trace-id form (DECISIONS 6): the decimal string of the lower 64 bits of an
/// OpenTelemetry trace id (up to 32 hex). `None` for invalid input.
///
/// ```
/// use grow_datadog_logs::mapping::trace_id_to_datadog;
/// assert_eq!(trace_id_to_datadog("4bf92f3577b34da6a3ce929d0e0e4736").as_deref(),
///            Some("11803532876627986230"));
/// ```
pub fn trace_id_to_datadog(trace_id: &str) -> Option<String> {
    let hex = trace_id.trim().to_ascii_lowercase();
    if hex.len() > 32 || !is_hex(&hex) {
        return None;
    }
    let low = &hex[hex.len().saturating_sub(16)..];
    u64::from_str_radix(low, 16).ok().map(|n| n.to_string())
}

/// Datadog span-id form: the decimal string of an OpenTelemetry span id (up to 16 hex).
pub fn span_id_to_datadog(span_id: &str) -> Option<String> {
    let hex = span_id.trim().to_ascii_lowercase();
    if hex.len() > 16 || !is_hex(&hex) {
        return None;
    }
    u64::from_str_radix(&hex, 16).ok().map(|n| n.to_string())
}

/// Converts a Datadog id (decimal string or number of up to 64 bits, or lower hex) to lower
/// hex left-padded to `length` characters. `None` for invalid or zero ids.
pub fn datadog_id_to_hex(id: &Value, length: usize) -> Option<String> {
    let text = match id {
        Value::Number(n) => n.as_u64().filter(|n| *n > 0)?.to_string(),
        Value::String(s) => s.trim().to_ascii_lowercase(),
        _ => return None,
    };
    let hex = if !text.is_empty() && text.len() <= 20 && text.bytes().all(|b| b.is_ascii_digit()) {
        format!("{:x}", text.parse::<u64>().ok()?)
    } else if text.len() <= length && is_hex(&text) {
        text
    } else {
        return None;
    };
    let padded = format!("{hex:0>length$}");
    (!padded.bytes().all(|b| b == b'0')).then_some(padded)
}

/// Datadog `status` of a record: its severity text lower-cased, else the canonical severity
/// name (`trace` … `fatal`).
pub fn datadog_status(record: &LogRecord) -> String {
    let text = record.severity_text.trim();
    if text.is_empty() {
        record.severity.text().to_ascii_lowercase()
    } else {
        text.to_lowercase()
    }
}

/// OpenTelemetry severity of a Datadog `status` (unknown values → `Info`).
pub fn severity_from_status(status: &str) -> Severity {
    match status.trim().to_ascii_lowercase().as_str() {
        "emerg" | "emergency" | "alert" | "crit" | "critical" | "fatal" | "panic" => {
            Severity::Fatal
        }
        "err" | "error" => Severity::Error,
        "warn" | "warning" => Severity::Warn,
        "debug" => Severity::Debug,
        "trace" => Severity::Trace,
        _ => Severity::Info,
    }
}

fn string_attr(value: Option<&AttrValue>) -> Option<String> {
    let text = match value? {
        Value::Null => return None,
        Value::String(s) => s.clone(),
        other => other.to_string(),
    };
    (!text.trim().is_empty()).then_some(text)
}

/// Builds the `ddtags` value: `env:`, `version:`, then the configured tags; comma-joined,
/// de-duplicated, whitespace and commas inside a tag replaced by `_`. `None` without tags.
pub fn build_ddtags(env: Option<&str>, version: Option<&str>, tags: &[String]) -> Option<String> {
    let mut out: Vec<String> = Vec::new();
    let mut add = |tag: &str| {
        let mut clean = String::with_capacity(tag.len());
        let mut in_sep = false;
        for c in tag.chars() {
            if c == ',' || c.is_whitespace() {
                if !in_sep {
                    clean.push('_');
                }
                in_sep = true;
            } else {
                clean.push(c);
                in_sep = false;
            }
        }
        if !clean.is_empty() && !out.contains(&clean) {
            out.push(clean);
        }
    };
    if let Some(env) = env {
        add(&format!("env:{env}"));
    }
    if let Some(version) = version {
        add(&format!("version:{version}"));
    }
    for tag in tags {
        add(tag);
    }
    (!out.is_empty()).then(|| out.join(","))
}

fn epoch_millis(t: SystemTime) -> i64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => i64::try_from(d.as_millis()).unwrap_or(i64::MAX),
        Err(e) => -i64::try_from(e.duration().as_millis()).unwrap_or(i64::MAX),
    }
}

/// Converts a [`LogRecord`] into a Datadog intake entry (API.md §5.2): `message`, `timestamp`
/// (epoch ms), `status`, `service`, `hostname`, `ddsource`, `ddtags`, `dd.trace_id` /
/// `dd.span_id` (decimal, DECISIONS 6) plus `otel.trace_id` / `otel.span_id` (lower hex,
/// lossless), then the record attributes (other resource keys are not sent). User attributes
/// named like a reserved key are prefixed with `attr.`.
pub fn to_datadog_entry(record: &LogRecord, defaults: &EntryDefaults) -> Map<String, Value> {
    let mut entry = Map::new();
    for (key, value) in &record.attributes {
        let key = if RESERVED_KEYS.contains(&key.as_str()) {
            format!("{RESERVED_PREFIX}{key}")
        } else {
            key.clone()
        };
        entry.insert(key, value.clone());
    }
    let res = &record.resource;
    entry.insert("message".into(), Value::String(record.body.clone()));
    entry.insert("timestamp".into(), epoch_millis(record.timestamp).into());
    entry.insert("status".into(), datadog_status(record).into());
    if let Some(s) = string_attr(res.get("service.name")).or_else(|| defaults.service.clone()) {
        entry.insert("service".into(), s.into());
    }
    if let Some(h) = string_attr(res.get("host.name")).or_else(|| defaults.hostname.clone()) {
        entry.insert("hostname".into(), h.into());
    }
    if let Some(src) = &defaults.source {
        entry.insert("ddsource".into(), src.clone().into());
    }
    let env = string_attr(res.get("deployment.environment")).or_else(|| defaults.env.clone());
    let version = string_attr(res.get("service.version")).or_else(|| defaults.version.clone());
    if let Some(tags) = build_ddtags(env.as_deref(), version.as_deref(), &defaults.tags) {
        entry.insert("ddtags".into(), tags.into());
    }
    if let Some(trace) = record.trace_id.as_deref() {
        if let Some(dd_trace) = trace_id_to_datadog(trace) {
            entry.insert("dd.trace_id".into(), dd_trace.into());
            entry.insert(
                "otel.trace_id".into(),
                format!("{:0>32}", trace.trim().to_ascii_lowercase()).into(),
            );
            if let Some(span) = record.span_id.as_deref() {
                if let Some(dd_span) = span_id_to_datadog(span) {
                    entry.insert("dd.span_id".into(), dd_span.into());
                    entry.insert(
                        "otel.span_id".into(),
                        format!("{:0>16}", span.trim().to_ascii_lowercase()).into(),
                    );
                }
            }
        }
    }
    entry
}

/// Bytes `c` takes inside a JSON string as written by `serde_json`.
fn json_char_len(c: char) -> usize {
    match c {
        '"' | '\\' | '\n' | '\r' | '\t' | '\u{8}' | '\u{c}' => 2,
        c if (c as u32) < 0x20 => 6,
        c => c.len_utf8(),
    }
}

/// One serialized intake entry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SerializedEntry {
    /// Compact JSON object.
    pub json: String,
    /// Whether the message was truncated.
    pub truncated: bool,
}

/// Serializes an entry so it is at most `max_bytes` (DECISIONS 7). An oversized entry gets its
/// `message` cut at a character boundary and `grow.truncated: true`. `None` when even an empty
/// message does not fit (the metadata alone is too large).
pub fn serialize_entry(entry: &Map<String, Value>, max_bytes: usize) -> Option<SerializedEntry> {
    let json = Value::Object(entry.clone()).to_string();
    if json.len() <= max_bytes {
        return Some(SerializedEntry {
            json,
            truncated: false,
        });
    }
    let message = entry
        .get("message")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    let mut base = entry.clone();
    base.insert(TRUNCATED_ATTRIBUTE.into(), Value::Bool(true));
    base.insert("message".into(), Value::String(String::new()));
    let overhead = Value::Object(base.clone()).to_string().len();
    if overhead > max_bytes {
        return None;
    }
    let budget = max_bytes - overhead;
    let mut used = 0;
    let mut end = 0;
    for (i, c) in message.char_indices() {
        let n = json_char_len(c);
        if used + n > budget {
            break;
        }
        used += n;
        end = i + c.len_utf8();
    }
    base.insert("message".into(), Value::String(message[..end].to_owned()));
    Some(SerializedEntry {
        json: Value::Object(base).to_string(),
        truncated: true,
    })
}

/// One log event returned by the Datadog search API, flattened.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct LogEvent {
    /// Datadog log id.
    pub id: String,
    /// Event time.
    pub timestamp: Option<SystemTime>,
    /// Reserved `host`.
    pub host: Option<String>,
    /// Reserved `service`.
    pub service: Option<String>,
    /// Reserved `status`.
    pub status: Option<String>,
    /// Message text.
    pub message: String,
    /// Tags (`env:test`, …).
    pub tags: Vec<String>,
    /// Custom attributes (`data[].attributes.attributes`), as returned (nested objects).
    pub attributes: Attributes,
}

/// Parses an RFC 3339 timestamp (`Z` or numeric offset).
pub(crate) fn parse_rfc3339(text: &str) -> Option<SystemTime> {
    let s = text.trim();
    let tail = s
        .len()
        .checked_sub(6)
        .and_then(|i| s.get(i..).map(|t| (i, t)));
    if let Some((i, offset)) = tail.filter(|(i, t)| *i > 10 && t.starts_with(['+', '-'])) {
        let h: u64 = offset.get(1..3)?.parse().ok()?;
        let m: u64 = offset.get(4..6)?.parse().ok()?;
        if offset.as_bytes()[3] != b':' {
            return None;
        }
        let t = humantime::parse_rfc3339_weak(&s[..i]).ok()?;
        let shift = std::time::Duration::from_secs(h * 3600 + m * 60);
        return if offset.starts_with('+') {
            t.checked_sub(shift)
        } else {
            t.checked_add(shift)
        };
    }
    humantime::parse_rfc3339_weak(s.trim_end_matches(['Z', 'z'])).ok()
}

impl LogEvent {
    /// Parses one `data[]` item of a search response. `None` when it is not an object.
    pub fn from_json(item: &Value) -> Option<LogEvent> {
        let item = item.as_object()?;
        let empty = Map::new();
        let attrs = item
            .get("attributes")
            .and_then(Value::as_object)
            .unwrap_or(&empty);
        let s = |k: &str| attrs.get(k).and_then(Value::as_str).map(str::to_owned);
        Some(LogEvent {
            id: item
                .get("id")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned(),
            timestamp: s("timestamp").as_deref().and_then(parse_rfc3339),
            host: s("host"),
            service: s("service"),
            status: s("status"),
            message: s("message").unwrap_or_default(),
            tags: attrs
                .get("tags")
                .and_then(Value::as_array)
                .map(|t| {
                    t.iter()
                        .filter_map(Value::as_str)
                        .map(str::to_owned)
                        .collect()
                })
                .unwrap_or_default(),
            attributes: attrs
                .get("attributes")
                .and_then(Value::as_object)
                .map(|m| m.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
                .unwrap_or_default(),
        })
    }

    /// Normalizes the event into a [`LogRecord`] (API.md §6.1): `status` → severity,
    /// `service`/`host`/`env:`/`version:` → resource, custom attributes plus `datadog.log_id`
    /// and `datadog.tags`; trace ids from `otel.trace_id` / `otel.span_id`, else from
    /// `dd.trace_id` / `dd.span_id` (decimal → hex; only the lower 64 bits survive that way).
    pub fn to_log_record(&self) -> LogRecord {
        let status = self.status.clone().unwrap_or_else(|| "info".to_owned());
        let mut resource = Attributes::new();
        if let Some(s) = &self.service {
            resource.insert("service.name".into(), s.clone().into());
        }
        if let Some(h) = &self.host {
            resource.insert("host.name".into(), h.clone().into());
        }
        for tag in &self.tags {
            if let Some(v) = tag.strip_prefix("env:") {
                resource
                    .entry("deployment.environment".into())
                    .or_insert_with(|| v.into());
            } else if let Some(v) = tag.strip_prefix("version:") {
                resource
                    .entry("service.version".into())
                    .or_insert_with(|| v.into());
            }
        }
        let mut attributes = self.attributes.clone();
        if !self.id.is_empty() {
            attributes.insert("datadog.log_id".into(), self.id.clone().into());
        }
        if !self.tags.is_empty() {
            attributes.insert("datadog.tags".into(), self.tags.clone().into());
        }
        let mut record = LogRecord::new(severity_from_status(&status), self.message.clone());
        record.timestamp = self.timestamp.unwrap_or(UNIX_EPOCH);
        record.severity_text = status;
        record.attributes = attributes;
        record.resource = resource;
        let id = |ns: &str, key: &str, len: usize| {
            dotted(&self.attributes, ns, key).and_then(|v| datadog_id_to_hex(v, len))
        };
        record.trace_id = id("otel", "trace_id", 32).or_else(|| id("dd", "trace_id", 32));
        if record.trace_id.is_some() {
            record.span_id = id("otel", "span_id", 16).or_else(|| id("dd", "span_id", 16));
        }
        record
    }
}

/// Reads `<ns>.<key>` from a flat key or a nested object.
fn dotted<'a>(attrs: &'a Attributes, ns: &str, key: &str) -> Option<&'a Value> {
    attrs
        .get(&format!("{ns}.{key}"))
        .or_else(|| attrs.get(ns).and_then(|v| v.get(key)))
}
