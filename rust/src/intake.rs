// Service-owned: the Datadog logs intake exporter (API.md §5; DECISIONS 4, 7).

use std::fmt;
use std::io::Write;
use std::sync::Arc;

use async_trait::async_trait;
use flate2::write::GzEncoder;
use flate2::Compression;

use crate::client::Inner;
use crate::core::{Error, Result};
use crate::http::{send, HEADER_API_KEY};
use crate::logging::{ExportOptions, ExportResult, LogExporter, LogRecord};
use crate::mapping::{
    serialize_entry, to_datadog_entry, EntryDefaults, MAX_ENTRIES_PER_REQUEST, MAX_LOG_BYTES,
    MAX_PAYLOAD_BYTES,
};

/// Path of the logs intake.
pub const INTAKE_PATH: &str = "/api/v2/logs";

/// One intake request body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IntakePayload {
    /// JSON array of entries.
    pub body: String,
    /// Number of entries.
    pub entries: usize,
}

/// Records mapped and split into intake request bodies.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct EncodedBatch {
    /// Request bodies, each within the intake limits.
    pub payloads: Vec<IntakePayload>,
    /// Records dropped because their metadata alone exceeds [`MAX_LOG_BYTES`].
    pub oversized: usize,
    /// Records whose message was truncated.
    pub truncated: usize,
}

/// [`LogExporter`] for `POST {intake}/api/v2/logs`: maps records to Datadog JSON, truncates
/// oversized messages, splits batches at 1000 entries / 5 MB, gzips (when asked) and sends with
/// header `DD-API-KEY` only. The intake POST is retried like an idempotent call (429, 5xx,
/// network errors; duplicates are possible after an ambiguous failure).
#[derive(Clone)]
pub struct DatadogLogExporter {
    inner: Arc<Inner>,
    defaults: EntryDefaults,
}

impl fmt::Debug for DatadogLogExporter {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DatadogLogExporter")
            .field("defaults", &self.defaults)
            .finish_non_exhaustive()
    }
}

impl DatadogLogExporter {
    pub(crate) fn new(inner: Arc<Inner>) -> Self {
        let c = &inner.config;
        let defaults = EntryDefaults {
            service: c.service().map(str::to_owned),
            hostname: c.hostname().map(str::to_owned),
            env: c.env().map(str::to_owned),
            version: c.version().map(str::to_owned),
            tags: c.tags().to_vec(),
            source: Some(c.source().to_owned()),
        };
        DatadogLogExporter { inner, defaults }
    }

    /// The defaults applied to records (service, hostname, env, version, tags, source).
    pub fn defaults(&self) -> &EntryDefaults {
        &self.defaults
    }

    /// Maps and splits records into intake request bodies (no I/O).
    pub fn encode(&self, records: &[LogRecord]) -> EncodedBatch {
        let mut out = EncodedBatch::default();
        let mut parts: Vec<String> = Vec::new();
        let mut bytes = 2;
        let flush = |parts: &mut Vec<String>, out: &mut EncodedBatch| {
            if !parts.is_empty() {
                out.payloads.push(IntakePayload {
                    body: format!("[{}]", parts.join(",")),
                    entries: parts.len(),
                });
                parts.clear();
            }
        };
        for record in records {
            let Some(entry) =
                serialize_entry(&to_datadog_entry(record, &self.defaults), MAX_LOG_BYTES)
            else {
                out.oversized += 1;
                continue;
            };
            if entry.truncated {
                out.truncated += 1;
            }
            let extra = entry.json.len() + usize::from(!parts.is_empty());
            if parts.len() >= MAX_ENTRIES_PER_REQUEST
                || (!parts.is_empty() && bytes + extra > MAX_PAYLOAD_BYTES)
            {
                flush(&mut parts, &mut out);
                bytes = 2;
            }
            bytes += entry.json.len() + usize::from(!parts.is_empty());
            parts.push(entry.json);
        }
        flush(&mut parts, &mut out);
        out
    }

    /// Posts one JSON array body to the intake (gzip when `gzip`). Returns the mapped error.
    pub async fn send_payload(&self, body: &str, gzip: bool) -> Result<()> {
        let payload: bytes::Bytes = if gzip {
            // Writing into a Vec cannot fail.
            let mut enc = GzEncoder::new(Vec::new(), Compression::default());
            let _ = enc.write_all(body.as_bytes());
            enc.finish().unwrap_or_default().into()
        } else {
            bytes::Bytes::copy_from_slice(body.as_bytes())
        };
        let url = format!(
            "{}{INTAKE_PATH}",
            self.inner
                .config
                .intake_url()
                .as_str()
                .trim_end_matches('/')
        );
        let transport = &self.inner.transport;
        let key = self.inner.config.api_key();
        send(transport, "logs.send", "POST", &url, || {
            let mut req = transport
                .post(&url)
                .secret_header(HEADER_API_KEY, key)
                .body(payload.clone(), "application/json")
                .idempotent(true);
            if gzip {
                req = req.header("content-encoding", "gzip");
            }
            req
        })
        .await
        .map(|_| ())
    }
}

#[async_trait]
impl LogExporter for DatadogLogExporter {
    /// Sends the records. A failure of the first request fails the whole batch (the batch
    /// processor retries retryable errors); a failure of a later request returns the partial
    /// result with the error. Records that cannot be made small enough are rejected with a
    /// `VALIDATION` error in the result.
    async fn export(&self, records: &[LogRecord], opts: ExportOptions) -> Result<ExportResult> {
        let encoded = self.encode(records);
        let mut accepted = 0;
        for payload in &encoded.payloads {
            if let Err(err) = self.send_payload(&payload.body, opts.gzip).await {
                if accepted == 0 {
                    return Err(err);
                }
                return Ok(ExportResult::partial(
                    accepted,
                    records.len() - accepted,
                    Some(err),
                ));
            }
            accepted += payload.entries;
        }
        if encoded.oversized > 0 {
            return Ok(ExportResult::partial(
                accepted,
                encoded.oversized,
                Some(Error::validation(format!(
                    "logs.send: {} record(s) dropped: metadata exceeds {MAX_LOG_BYTES} bytes",
                    encoded.oversized
                ))),
            ));
        }
        Ok(ExportResult::accepted(accepted))
    }
}
