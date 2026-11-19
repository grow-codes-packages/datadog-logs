// Taken over from sdk-kit 0.1.0: the Datadog client against a mock of the Datadog API.
//! `DatadogLogsClient`: config, site resolver, health, ping, validate_api_key, server_version,
//! close, error mapping and the rate-limit retry hint.

mod common;
mod dd;

use std::sync::Arc;
use std::time::Duration;

use common::{closed_port, Recorder};
use dd::{builder, client, fast_retry, requests, respond, API_KEY, APP_KEY};
use grow_datadog_logs as sdk;
use sdk::core::{EnvReader, ErrorCode};
use sdk::{map_datadog_error, parse_tags, resolve_site, Error, SITES};
use serde_json::json;
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use sdk::DatadogLogsClient as Client;
use sdk::DatadogLogsConfig as Config;

fn var(name: &str) -> String {
    format!("GROW_DATADOG_LOGS_{name}")
}

#[test]
fn site_resolver() {
    for site in SITES {
        let ep = resolve_site(site).unwrap();
        assert_eq!(ep.intake_url, format!("https://http-intake.logs.{site}"));
        assert_eq!(ep.api_url, format!("https://api.{site}"));
    }
    assert_eq!(
        resolve_site(" DatadogHQ.EU ").unwrap().api_url,
        "https://api.datadoghq.eu"
    );
    assert_eq!(
        resolve_site("").unwrap().api_url,
        "https://api.datadoghq.com"
    );
    let err = resolve_site("app.datadoghq.com").unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    assert!(err.message().contains("datadoghq.eu"));
}

#[test]
fn tags_parsing() {
    assert_eq!(parse_tags("a:1, b:2\tc:3,,  "), vec!["a:1", "b:2", "c:3"]);
    assert!(parse_tags("").is_empty());
}

#[test]
fn config_defaults_and_endpoints() {
    let c = Config::builder().api_key("k").build().unwrap();
    assert_eq!(c.site(), "datadoghq.com");
    assert_eq!(c.api_url().as_str(), "https://api.datadoghq.com/");
    assert_eq!(
        c.intake_url().as_str(),
        "https://http-intake.logs.datadoghq.com/"
    );
    assert_eq!(c.source(), "grow-sdk");
    assert!(c.service().is_none() && c.env().is_none() && c.version().is_none());
    assert!(c.hostname().is_none() && c.tags().is_empty() && c.app_key().is_none());
    let ep = c.endpoints();
    assert_eq!(ep.api_url, sdk::DEFAULT_URL);
    assert_eq!(ep.intake_url, sdk::DEFAULT_INTAKE_URL);

    let eu = Config::builder()
        .api_key("k")
        .site("datadoghq.eu")
        .build()
        .unwrap();
    assert_eq!(eu.endpoints().api_url, "https://api.datadoghq.eu");

    // one URL override: the other one still comes from the site
    let c = Config::builder()
        .api_key("k")
        .site("us3.datadoghq.com")
        .intake_url("http://intake.local:8080/")
        .build()
        .unwrap();
    assert_eq!(c.endpoints().intake_url, "http://intake.local:8080");
    assert_eq!(c.endpoints().api_url, "https://api.us3.datadoghq.com");

    // both URLs set: any site string is accepted
    let c = Config::builder()
        .api_key("k")
        .site("not-a-site")
        .intake_url("http://i")
        .api_url("http://a")
        .build()
        .unwrap();
    assert_eq!(c.site(), "not-a-site");

    for (name, b) in [
        (
            "unknown site",
            Config::builder().api_key("k").site("example.com"),
        ),
        ("no key", Config::builder()),
        ("empty key", Config::builder().api_key("")),
        (
            "bad intake",
            Config::builder().api_key("k").intake_url("::nope"),
        ),
        (
            "ftp intake",
            Config::builder().api_key("k").intake_url("ftp://x"),
        ),
        ("bad api", Config::builder().api_key("k").api_url("::nope")),
    ] {
        assert_eq!(b.build().unwrap_err().code(), ErrorCode::Config, "{name}");
    }
}

#[test]
fn config_from_env_precedence() {
    let env = EnvReader::from_map([
        ("DD_API_KEY", "dd-key"),
        ("DD_APPLICATION_KEY", "dd-app"),
        ("DD_SITE", "datadoghq.eu"),
        ("DD_SERVICE", "dd-svc"),
        ("DD_ENV", "dd-env"),
        ("DD_VERSION", "1.0"),
        ("DD_TAGS", "team:sdk lang:rust"),
        ("DD_HOSTNAME", "dd-host"),
    ]);
    let c = Config::builder().build_with_env(&env).unwrap();
    assert_eq!(c.api_key().expose_secret(), "dd-key");
    assert_eq!(c.app_key().unwrap().expose_secret(), "dd-app");
    assert_eq!(c.site(), "datadoghq.eu");
    assert_eq!(c.service(), Some("dd-svc"));
    assert_eq!(c.env(), Some("dd-env"));
    assert_eq!(c.version(), Some("1.0"));
    assert_eq!(c.tags(), ["team:sdk", "lang:rust"]);
    assert_eq!(c.hostname(), Some("dd-host"));

    let env = EnvReader::from_map([
        ("DD_API_KEY".to_owned(), "dd-key".to_owned()),
        ("DD_APP_KEY".to_owned(), "dd-app-1".to_owned()),
        ("DD_SERVICE".to_owned(), "dd-svc".to_owned()),
        (var("API_KEY"), "grow-key".to_owned()),
        (var("SERVICE"), "grow-svc".to_owned()),
        (var("TAGS"), "a:1,b:2".to_owned()),
        (var("SOURCE"), "custom".to_owned()),
        (var("URL"), "http://fake:80".to_owned()),
        (var("API_URL"), "http://api:81".to_owned()),
    ]);
    let c = Config::builder()
        .service("explicit")
        .build_with_env(&env)
        .unwrap();
    assert_eq!(c.api_key().expose_secret(), "grow-key");
    assert_eq!(c.app_key().unwrap().expose_secret(), "dd-app-1");
    assert_eq!(c.service(), Some("explicit"));
    assert_eq!(c.tags(), ["a:1", "b:2"]);
    assert_eq!(c.source(), "custom");
    assert_eq!(c.endpoints().intake_url, "http://fake");
    assert_eq!(c.endpoints().api_url, "http://api:81");

    let c = Config::builder()
        .tags([" x:1 ", ""])
        .api_key("k")
        .build_with_env(&EnvReader::from_map([("DD_TAGS", "ignored:1")]))
        .unwrap();
    assert_eq!(c.tags(), ["x:1"]);
    let err = Config::builder()
        .build_with_env(&EnvReader::from_map([(
            var("CONNECT_TIMEOUT"),
            "soon".to_owned(),
        )]))
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
}

#[test]
fn config_debug_redacts_keys() {
    let c = Config::builder()
        .api_key("api-secret-value-1")
        .app_key("app-secret-value-2")
        .intake_url("https://u:intake-pw-3@intake.example")
        .build()
        .unwrap();
    let text = format!("{c:?}");
    for s in ["api-secret-value-1", "app-secret-value-2", "intake-pw-3"] {
        assert!(!text.contains(s), "{s} leaked: {text}");
    }
    assert!(text.contains("[REDACTED]"));
}

#[tokio::test]
async fn health_ping_validate() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/validate"))
        .and(header("dd-api-key", API_KEY))
        .respond_with(respond("validateApiKey.datadog_ok"))
        .mount(&server)
        .await;
    let c = client(&server);
    let h = c.health().await.unwrap();
    assert!(h.healthy);
    assert_eq!(h.status, "ok");
    assert_eq!(h.details["apiKeyValid"], "true");
    assert_eq!(h.details["httpStatus"], "200");
    assert!(c.ping().await.unwrap() > Duration::ZERO);
    assert!(c.validate_api_key().await.unwrap());
    let err = c.server_version().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unsupported);
    // the application key never goes to /api/v1/validate
    for r in requests(&server, "/api/v1/validate").await {
        assert!(r.headers.get("dd-application-key").is_none());
        let ua = r.headers.get("user-agent").unwrap().to_str().unwrap();
        assert!(ua.starts_with("grow-codes-datadog-logs/"), "{ua}");
    }
    assert_eq!(c.endpoints().api_url, server.uri());
    assert!(!c.is_closed());
    c.close().await;
    c.close().await;
    assert!(c.is_closed());
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    assert_eq!(c.health().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn rejected_key() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(respond("validateApiKey.403_invalid_key"))
        .mount(&server)
        .await;
    let c = client(&server);
    assert!(!c.validate_api_key().await.unwrap());
    let err = c.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);
    assert_eq!(err.http_status(), Some(403));
    assert!(err.message().contains("Forbidden"), "{err}");
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unauthorized");
    assert_eq!(h.details["apiKeyValid"], "false");
    assert_eq!(h.details["httpStatus"], "403");
}

async fn validate_answers(template: ResponseTemplate) -> Client {
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(template)
        .mount(&server)
        .await;
    let c = Client::new(builder(&server).retry(fast_retry(0)).build().unwrap()).unwrap();
    // keep the server alive for the client's lifetime
    std::mem::forget(server);
    c
}

#[tokio::test]
async fn health_statuses_and_unexpected_answers() {
    let cases = [
        (401, "unauthorized", false),
        (429, "degraded", false),
        (500, "unhealthy", false),
        (503, "unhealthy", false),
    ];
    for (status, word, healthy) in cases {
        let c = validate_answers(ResponseTemplate::new(status)).await;
        let h = c.health().await.unwrap();
        assert_eq!((h.status.as_str(), h.healthy), (word, healthy), "{status}");
    }
    // fakeintake's un-stubbed 200 with an empty body
    let c = validate_answers(ResponseTemplate::new(200)).await;
    let h = c.health().await.unwrap();
    assert!(h.healthy);
    assert_eq!(h.details["apiKeyValid"], "false");
    let err = c.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
    assert!(!err.is_retryable());
    assert_eq!(
        c.validate_api_key().await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
    let c =
        validate_answers(ResponseTemplate::new(200).set_body_json(json!({"valid": false}))).await;
    assert!(!c.validate_api_key().await.unwrap());
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Unavailable);
    let c = validate_answers(
        ResponseTemplate::new(401).set_body_json(json!({"errors": ["Unauthorized"]})),
    )
    .await;
    assert_eq!(
        c.validate_api_key().await.unwrap_err().code(),
        ErrorCode::Authentication
    );
}

#[tokio::test]
async fn unreachable_service() {
    let c = Client::new(
        Config::builder()
            .url(format!("http://127.0.0.1:{}", closed_port()))
            .api_key("k")
            .retry(fast_retry(0))
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unreachable");
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Unavailable);
}

#[tokio::test]
async fn rate_limit_reset_is_the_retry_hint() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(
            ResponseTemplate::new(429)
                .insert_header("x-ratelimit-reset", "0.05")
                .set_body_json(json!({"errors": ["Too many requests"]})),
        )
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(respond("validateApiKey.datadog_ok"))
        .mount(&server)
        .await;
    let rec = Recorder::new();
    let c = Client::new(builder(&server).hooks(rec.clone()).build().unwrap()).unwrap();
    let started = std::time::Instant::now();
    assert!(c.validate_api_key().await.unwrap());
    assert!(started.elapsed() >= Duration::from_millis(50));
    let errors = rec.errors();
    assert_eq!(errors.len(), 1);
    assert!(errors[0].will_retry);
    assert_eq!(errors[0].retry_delay, Some(Duration::from_millis(50)));
    assert_eq!(errors[0].method, "GET");
    assert!(!rec.dump().contains(API_KEY));
}

#[tokio::test]
async fn rate_limit_gives_up() {
    // Retry-After wins over X-RateLimit-Reset; too long a hint fails at once
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(
            ResponseTemplate::new(429)
                .insert_header("retry-after", "3600")
                .insert_header("x-ratelimit-reset", "1")
                .insert_header("x-request-id", "req-1")
                .set_body_json(json!({"errors": ["Too many requests", "slow down"]})),
        )
        .mount(&server)
        .await;
    let c = client(&server);
    let err = c.validate_api_key().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::RateLimited);
    assert_eq!(err.retry_after(), Some(Duration::from_secs(3600)));
    assert_eq!(err.request_id(), Some("req-1"));
    assert!(
        err.message().contains("Too many requests, slow down"),
        "{err}"
    );
    assert_eq!(requests(&server, "/api/v1/validate").await.len(), 1);

    // without hints: retried max_retries times, then RATE_LIMITED
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(ResponseTemplate::new(429).set_body_string("<html>busy</html>"))
        .mount(&server)
        .await;
    let c = client(&server);
    let err = c.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::RateLimited);
    assert!(err.retry_after().is_none());
    assert_eq!(requests(&server, "/api/v1/validate").await.len(), 3);

    // a bad hint is ignored; the total timeout stops the retries
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(
            ResponseTemplate::new(429)
                .insert_header("x-ratelimit-reset", "soon")
                .set_body_string("plain text busy"),
        )
        .mount(&server)
        .await;
    let c = Client::new(
        builder(&server)
            .retry(sdk::RetryPolicy {
                initial_backoff: Duration::from_secs(5),
                max_backoff: Duration::from_secs(5),
                ..fast_retry(5)
            })
            .total_timeout(Duration::from_millis(500))
            .build()
            .unwrap(),
    )
    .unwrap();
    let err = c.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::RateLimited);
    assert!(err.message().contains("plain text busy"), "{err}");
}

#[test]
fn error_mapping() {
    let e = map_datadog_error(
        Error::from_status(403, "x: forbidden (HTTP 403)").with_request_id("r-1"),
    );
    assert_eq!(e.code(), ErrorCode::Authentication);
    assert_eq!(e.http_status(), Some(403));
    assert_eq!(e.request_id(), Some("r-1"));
    assert!(!e.is_retryable());
    let e = map_datadog_error(Error::from_status(403, "no id"));
    assert_eq!(e.request_id(), None);
    let other = map_datadog_error(Error::from_status(404, "missing"));
    assert_eq!(other.code(), ErrorCode::NotFound);
    let unrelated = map_datadog_error(Error::validation("v").with_status(403));
    assert_eq!(unrelated.code(), ErrorCode::Validation);
}

#[tokio::test]
async fn query_needs_app_key_and_secrets_are_redacted() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/validate"))
        .respond_with(
            ResponseTemplate::new(400)
                .set_body_json(json!({"errors": [format!("bad key {API_KEY} and {APP_KEY}")]})),
        )
        .mount(&server)
        .await;
    let c = client(&server);
    assert!(c.query().is_ok());
    let err = c.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    let text = format!("{err} {err:?}");
    assert!(!text.contains(API_KEY) && !text.contains(APP_KEY), "{text}");

    let no_app = Client::new(
        Config::builder()
            .url(server.uri())
            .api_key(API_KEY)
            .build()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(no_app.query().unwrap_err().code(), ErrorCode::Config);
    assert!(format!("{no_app:?}").contains("DatadogLogsClient"));
    let _ = no_app.transport();
    let hooks: Arc<Recorder> = Recorder::new();
    let _ = hooks;
    let _ = Client::from_env();
}
