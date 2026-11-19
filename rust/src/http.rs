// Service-owned: Datadog-specific HTTP plumbing on top of the generated transport
// (DECISIONS 3: 403 → AUTHENTICATION; DECISIONS 5: X-RateLimit-Reset as retry hint).

use std::time::{Duration, SystemTime};

use serde_json::Value;
use tokio::time::Instant;

use crate::core::hooks::{guarded, ErrorInfo};
use crate::core::redact::{redact_url, truncate};
use crate::core::retry::parse_retry_after;
use crate::core::transport::{RequestBuilder, MAX_VENDOR_MESSAGE};
use crate::core::{Error, ErrorCode, HttpTransport, Response, Result};

/// Header carrying the API key on every request.
pub const HEADER_API_KEY: &str = "DD-API-KEY";
/// Header carrying the application key on query requests only.
pub const HEADER_APP_KEY: &str = "DD-APPLICATION-KEY";
/// Rate-limit header used as the retry hint when `Retry-After` is missing (seconds).
pub const RATE_LIMIT_RESET_HEADER: &str = "x-ratelimit-reset";

/// Maps Datadog's 403 (missing or invalid key, or a key without the needed permission, which
/// Datadog does not tell apart) onto `AUTHENTICATION`, keeping the HTTP status 403, the message
/// and the request id. Other errors are returned unchanged.
pub fn map_datadog_error(err: Error) -> Error {
    if err.http_status() != Some(403) || err.code() != ErrorCode::PermissionDenied {
        return err;
    }
    let mut out = Error::new(ErrorCode::Authentication, err.message()).with_status(403);
    if let Some(id) = err.request_id() {
        out = out.with_request_id(id);
    }
    out.with_source(err)
}

/// Retry hint of a 429 response: `Retry-After`, else `X-RateLimit-Reset` (seconds).
pub(crate) fn rate_limit_hint(resp: &Response) -> Option<Duration> {
    if let Some(ra) = resp
        .header("retry-after")
        .and_then(|v| parse_retry_after(v, SystemTime::now()))
    {
        return Some(ra);
    }
    let reset = resp
        .header(RATE_LIMIT_RESET_HEADER)?
        .trim()
        .parse::<f64>()
        .ok()?;
    (reset.is_finite() && reset >= 0.0).then(|| Duration::from_secs_f64(reset))
}

/// Short vendor error text: `{"errors":["a","b"]}` → `a, b`; also `{status,title,detail}`
/// objects and plain text. `None` for empty bodies.
pub(crate) fn vendor_text(body: &[u8]) -> Option<String> {
    let text = std::str::from_utf8(body).ok()?.trim();
    if text.is_empty() {
        return None;
    }
    let Ok(value) = serde_json::from_str::<Value>(text) else {
        return (!text.starts_with('<')).then(|| text.lines().next().unwrap_or("").to_owned());
    };
    let item = |v: &Value| match v {
        Value::String(s) => Some(s.clone()),
        Value::Object(m) => ["detail", "title", "message"]
            .iter()
            .find_map(|k| m.get(*k).and_then(Value::as_str).map(str::to_owned)),
        _ => None,
    };
    let parts: Vec<String> = match &value {
        Value::Object(m) => match m.get("errors") {
            Some(Value::Array(items)) => items.iter().filter_map(item).collect(),
            _ => item(&value).into_iter().collect(),
        },
        other => item(other).into_iter().collect(),
    };
    let joined = parts.join(", ");
    (!joined.trim().is_empty()).then_some(joined)
}

/// Sends the request built by `build` (called once per attempt) and applies the Datadog rules:
/// 429 is retried here so `X-RateLimit-Reset` works as a retry hint, every error goes through
/// [`map_datadog_error`].
pub(crate) async fn send<'a, F>(
    transport: &'a HttpTransport,
    operation: &str,
    method: &str,
    url: &str,
    build: F,
) -> Result<Response>
where
    F: Fn() -> RequestBuilder<'a>,
{
    let policy = transport.config().retry().clone();
    let deadline = Instant::now() + transport.config().total_timeout();
    let mut retry: u32 = 0;
    loop {
        let resp = build()
            .operation(operation)
            .ok_status(429)
            .send()
            .await
            .map_err(map_datadog_error)?;
        if resp.status() != 429 {
            return Ok(resp);
        }
        let hint = rate_limit_hint(&resp);
        let reason = match vendor_text(resp.bytes()) {
            Some(t) => format!(
                "{}: {}",
                ErrorCode::RateLimited.reason(),
                truncate(&transport.redact(&t), MAX_VENDOR_MESSAGE)
            ),
            None => ErrorCode::RateLimited.reason().to_owned(),
        };
        let mut err = Error::from_status(429, format!("{operation}: {reason} (HTTP 429)"));
        if let Some(id) = resp.request_id() {
            err = err.with_request_id(id);
        }
        if let Some(h) = hint {
            err = err.with_retry_after(h);
        }
        let mut delay = policy.backoff(retry);
        if let Some(h) = hint {
            delay = delay.max(h);
        }
        let give_up = retry >= policy.max_retries
            || hint.is_some_and(|h| h > policy.max_retry_after)
            || Instant::now() + delay >= deadline;
        emit_error(
            transport,
            method,
            url,
            &err,
            retry + 1,
            (!give_up).then_some(delay),
        );
        if give_up {
            return Err(err);
        }
        tokio::time::sleep(delay).await;
        retry += 1;
    }
}

fn emit_error(
    transport: &HttpTransport,
    method: &str,
    url: &str,
    err: &Error,
    attempt: u32,
    delay: Option<Duration>,
) {
    if let Some(hooks) = transport.config().hooks() {
        let info = ErrorInfo {
            method: method.to_owned(),
            url: transport.redact(&redact_url(url)),
            error: err.clone(),
            attempt,
            will_retry: delay.is_some(),
            retry_delay: delay,
        };
        guarded(|| hooks.on_error(&info));
    }
}
