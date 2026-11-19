# Configuration

`DatadogLogsConfig` holds every setting of a client. It is **immutable** after construction,
validated when it is built (invalid values raise a `CONFIG` error) and its string/debug form
**redacts the API key and the application key**.

## Datadog settings

| Setting | Default | Environment variables (in order) |
| - | - | - |
| `apiKey` — header `DD-API-KEY` (secret, **required**) | none | `GROW_DATADOG_LOGS_API_KEY`, `DD_API_KEY` |
| `appKey` — header `DD-APPLICATION-KEY`, query API only (secret) | none | `GROW_DATADOG_LOGS_APP_KEY`, `DD_APP_KEY`, `DD_APPLICATION_KEY` |
| `site` — Datadog site | `datadoghq.com` | `GROW_DATADOG_LOGS_SITE`, `DD_SITE` |
| `url` — one base URL for intake and API (proxy, fakeintake) | derived from `site` | `GROW_DATADOG_LOGS_URL` |
| `intakeUrl` — logs intake base URL | `url`, else `https://http-intake.logs.<site>` | `GROW_DATADOG_LOGS_INTAKE_URL` |
| `apiUrl` — API base URL (validate, search, aggregate, indexes) | `url`, else `https://api.<site>` | `GROW_DATADOG_LOGS_API_URL` |
| `service` — `service` of every log without a `service.name` resource | none | `GROW_DATADOG_LOGS_SERVICE`, `DD_SERVICE` |
| `env` — tag `env:<value>` without a `deployment.environment` resource | none | `GROW_DATADOG_LOGS_ENV`, `DD_ENV` |
| `version` — tag `version:<value>` without a `service.version` resource | none | `GROW_DATADOG_LOGS_VERSION`, `DD_VERSION` |
| `tags` — extra `key:value` tags | none | `GROW_DATADOG_LOGS_TAGS`, `DD_TAGS` |
| `hostname` — `hostname` without a `host.name` resource | none | `GROW_DATADOG_LOGS_HOSTNAME`, `DD_HOSTNAME` |
| `source` — `ddsource` (selects Datadog integration pipelines) | `grow-sdk` | `GROW_DATADOG_LOGS_SOURCE` |

Rules (identical in every language):

- **Sites**: `datadoghq.com`, `us3.datadoghq.com`, `us5.datadoghq.com`, `ap1.datadoghq.com`,
  `ap2.datadoghq.com`, `uk1.datadoghq.com`, `datadoghq.eu`, `ddog-gov.com`, `us2.ddog-gov.com`
  (trimmed, case-insensitive). Use the API site, not the UI host (`app.datadoghq.com`). An unknown
  site is a `CONFIG` error unless `url` or both `intakeUrl` and `apiUrl` are set.
- **Keys**: `apiKey` is required by every call, including `health()`. `appKey` is only needed for
  `client.query()` (which raises `CONFIG` without it) and is never sent to the intake. A blank key
  is a `CONFIG` error.
- **Tags**: `DD_TAGS` / `tags` as one string are split on commas and whitespace (the Datadog Agent
  convention), empty items and duplicates dropped. `ddtags` on the wire is `env:<env>`,
  `version:<version>`, then `tags`; whitespace or commas inside one tag become `_`.

## Common settings

| Setting | Default | Environment variable |
| - | - | - |
| `connectTimeout` — connection setup | 10 s | `GROW_DATADOG_LOGS_CONNECT_TIMEOUT` |
| `requestTimeout` — per attempt (headers + body) | 30 s | `GROW_DATADOG_LOGS_REQUEST_TIMEOUT` |
| `totalTimeout` — whole call incl. retries and backoff | 120 s | `GROW_DATADOG_LOGS_TOTAL_TIMEOUT` |
| `retry` — retry policy (below) | 3 retries | `GROW_DATADOG_LOGS_MAX_RETRIES` |
| `tls.caCertPath` / `tls.caCertPem` — extra CA | none | `GROW_DATADOG_LOGS_CA_CERT` (path) |
| `tls.clientCertPath` + `tls.clientKeyPath` — mutual TLS | none | `GROW_DATADOG_LOGS_CLIENT_CERT`, `GROW_DATADOG_LOGS_CLIENT_KEY` |
| `tls.insecureSkipVerify` — tests only, logs a warning once | `false` | `GROW_DATADOG_LOGS_INSECURE_SKIP_VERIFY` |
| `proxy` — proxy URL | none (environment proxies are **ignored**) | `GROW_DATADOG_LOGS_PROXY` |
| `headers` — extra request headers | `{}` | — |
| `userAgentSuffix` — appended to the User-Agent | none | — |
| `hooks` — request/response callbacks ([Hooks](hooks.md)) | none | — |
| `logger` — diagnostic logger ([Hooks](hooks.md)) | silent | — |

Every request sends `User-Agent: grow-codes-datadog-logs/<version> (<language>)`.

## From the environment

`fromEnv` resolves each setting with this precedence:

1. the value passed in code (overrides),
2. `GROW_DATADOG_LOGS_<NAME>`,
3. the Datadog variables (`DD_API_KEY`, `DD_SITE`, …) in the order of the table above,
4. the default.

Parsing rules:

- **Durations** accept integer milliseconds (`1500`) or Go-style units: `500ms`, `10s`, `2m`, `1h`,
  combinations such as `1m30s`.
- **Booleans** accept `true/false`, `1/0`, `yes/no`, `on/off` (any case).
- An **empty** variable counts as unset.
- A present but **invalid** value raises a `CONFIG` error that names the variable, never its value.

=== "TypeScript"

    ```ts
    import { DatadogLogsClient, DatadogLogsConfig } from "@grow-codes/datadog-logs";

    // Environment only
    const client = DatadogLogsClient.fromEnv();

    // Environment with explicit overrides (explicit values win)
    const config = DatadogLogsConfig.fromEnv({ service: "checkout", requestTimeoutMs: 5_000 });

    // Everything in code
    const explicit = new DatadogLogsConfig({
      apiKey: process.env.MY_DD_API_KEY,
      appKey: process.env.MY_DD_APP_KEY, // only for client.query()
      site: "datadoghq.eu",
      service: "checkout",
      env: "prod",
      version: "1.4.0",
      tags: ["team:payments"],
      totalTimeoutMs: 60_000,
      retry: { maxRetries: 5, initialBackoffMs: 200 },
      proxy: "http://proxy.internal:3128",
      userAgentSuffix: "my-app/1.4",
    });
    const custom = new DatadogLogsClient(explicit);
    console.log(String(explicit)); // keys shown as [REDACTED]
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_datadog_logs::{DatadogLogsClient, DatadogLogsConfig};

    // Environment only
    let client = DatadogLogsClient::from_env()?;

    // Environment with explicit overrides (explicit values win)
    let config = DatadogLogsConfig::builder()
        .service("checkout")
        .request_timeout(Duration::from_secs(5))
        .build_from_env()?;

    // Everything in code
    let explicit = DatadogLogsConfig::builder()
        .api_key(std::env::var("MY_DD_API_KEY").unwrap_or_default())
        .app_key(std::env::var("MY_DD_APP_KEY").unwrap_or_default()) // only for client.query()
        .site("datadoghq.eu")
        .service("checkout")
        .env("prod")
        .version("1.4.0")
        .tags(["team:payments"])
        .total_timeout(Duration::from_secs(60))
        .max_retries(5)
        .proxy("http://proxy.internal:3128")
        .user_agent_suffix("my-app/1.4")
        .build()?;
    let custom = DatadogLogsClient::new(explicit)?;
    println!("{:?}", custom.config()); // keys shown as [REDACTED]
    ```

=== "Go"

    ```go
    // Environment only
    client, err := datadoglogs.NewClientFromEnv()

    // Everything in code: zero values mean "use the default"
    cfg := datadoglogs.Config{
        CommonConfig: datadoglogs.CommonConfig{
            TotalTimeout:    time.Minute,
            Retry:           &datadoglogs.RetryPolicy{MaxRetries: 5, InitialBackoff: 200 * time.Millisecond},
            Proxy:           "http://proxy.internal:3128",
            UserAgentSuffix: "my-app/1.4",
        },
        APIKey:  datadoglogs.NewSecret(os.Getenv("MY_DD_API_KEY")),
        AppKey:  datadoglogs.NewSecret(os.Getenv("MY_DD_APP_KEY")), // only for client.Query()
        Site:    "datadoghq.eu",
        Service: "checkout",
        Env:     "prod",
        Version: "1.4.0",
        Tags:    []string{"team:payments"},
    }
    if err := cfg.Validate(); err != nil {
        log.Fatal(err) // errors.Is(err, datadoglogs.ErrConfig)
    }
    custom, err := datadoglogs.NewClient(cfg)

    // Environment with explicit overrides (explicit values win; nil env = process environment)
    merged, err := datadoglogs.ConfigFromEnvWith(datadoglogs.Config{Service: "checkout"}, nil)
    fmt.Println(merged) // keys shown as [REDACTED]
    ```

=== "Python"

    ```python
    import os
    from grow_datadog_logs import DatadogLogsClient, DatadogLogsConfig, RetryPolicy

    # Environment only
    client = DatadogLogsClient.from_env()

    # Environment with explicit overrides (explicit values win)
    config = DatadogLogsConfig.from_env(service="checkout", request_timeout=5.0)
    # a test environment instead of os.environ (positional-only, see Compatibility)
    test_config = DatadogLogsConfig.from_env({"DD_API_KEY": "test"}, env="ci")

    # Everything in code: durations are float seconds (datetime.timedelta also works)
    explicit = DatadogLogsConfig(
        api_key=os.environ["MY_DD_API_KEY"],
        app_key=os.environ.get("MY_DD_APP_KEY"),  # only for client.query()
        site="datadoghq.eu",
        service="checkout",
        env="prod",
        version="1.4.0",
        tags=("team:payments",),
        total_timeout=60.0,
        retry=RetryPolicy(max_retries=5, initial_backoff=0.2),
        proxy="http://proxy.internal:3128",
        user_agent_suffix="my-app/1.4",
    )
    custom = DatadogLogsClient(explicit)
    print(repr(explicit))  # keys shown as [REDACTED]
    ```

=== "Java"

    ```java
    import java.time.Duration;
    import java.util.List;
    import codes.grow.packages.datadoglogs.DatadogLogsClient;
    import codes.grow.packages.datadoglogs.DatadogLogsConfig;
    import codes.grow.packages.datadoglogs.core.RetryPolicy;

    // Environment only
    DatadogLogsClient client = DatadogLogsClient.fromEnv();

    // Everything in code
    DatadogLogsConfig config = DatadogLogsConfig.builder()
        .apiKey(System.getenv("MY_DD_API_KEY"))
        .appKey(System.getenv("MY_DD_APP_KEY")) // only for client.query()
        .site("datadoghq.eu")
        .service("checkout")
        .env("prod")
        .version("1.4.0")
        .tags(List.of("team:payments"))
        .totalTimeout(Duration.ofMinutes(1))
        .retry(RetryPolicy.builder().maxRetries(5).initialBackoff(Duration.ofMillis(200)).build())
        .proxy("http://proxy.internal:3128")
        .userAgentSuffix("my-app/1.4")
        .build();
    DatadogLogsClient custom = DatadogLogsClient.create(config);
    System.out.println(config); // keys shown as [REDACTED]
    ```

## Retry policy

| Field | Default | Meaning |
| - | - | - |
| `maxRetries` | 3 | retries after the first attempt (so up to 4 attempts) |
| `initialBackoff` | 100 ms | backoff cap of the first retry |
| `maxBackoff` | 10 s | cap of every backoff |
| `multiplier` | 2 | growth of the cap per retry |
| `maxRetryAfter` | 60 s | longest server-requested wait the client accepts |

Backoff for retry *n* (0-based) is **full jitter**: a random wait between 0 and
`min(maxBackoff, initialBackoff × multiplier^n)`.

Datadog rules on top of the shared transport:

- Every Datadog request is retried on HTTP 429, 408, 500, 502, 503, 504 and on network errors. This
  includes the intake `POST` and the read-only search/aggregate `POST`s: Datadog has no idempotency
  key, so a retried intake request can store a batch twice when the first answer was lost.
- On 429 and 503 the client honours `Retry-After`; when it is missing, **`X-RateLimit-Reset`**
  (seconds until the rate-limit window resets) is the hint. It waits `max(hint, backoff)`. When the
  hint exceeds `maxRetryAfter` or the remaining `totalTimeout`, it stops at once with
  `RATE_LIMITED`/`UNAVAILABLE` and sets `retryAfter` on the error.
- Every 403 from Datadog (missing or invalid key, or a key without the needed permission, which
  Datadog does not tell apart) becomes `AUTHENTICATION` with `httpStatus` 403.

Set `maxRetries` to 0 to turn retries off.

## Timeouts and cancellation

- `connectTimeout` bounds connection setup, `requestTimeout` each attempt, `totalTimeout` the whole
  call including backoff sleeps. An exhausted timeout raises `TIMEOUT`.
- Cancellation is honoured during requests and backoff sleeps and raises `CANCELLED`:
  TypeScript `{ signal }` (`AbortSignal`), Rust dropping the future, Go `ctx`, Python `cancel=`
  (`CancelToken`) or task cancellation, Java `…Async` futures (`cancel(true)`).
- Every call on a closed client raises `CANCELLED`.

## TLS and proxies

- Add a private CA with `tls.caCertPath` (or the PEM text in `tls.caCertPem`).
- Mutual TLS needs `tls.clientCertPath` and `tls.clientKeyPath` together.
- `tls.insecureSkipVerify` disables certificate checks. Use it for local tests only; the client
  logs a warning once.
- `HTTP_PROXY`/`HTTPS_PROXY` are **ignored** so that keys never leave through an unexpected
  proxy. Set `proxy` (or `GROW_DATADOG_LOGS_PROXY`) to use one.
