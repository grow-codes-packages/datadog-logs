# Grow Codes SDK for Datadog Log Management

[![CI](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/grow-codes-packages/datadog-logs?sort=semver)](https://github.com/grow-codes-packages/datadog-logs/releases)
[![Docs](https://img.shields.io/badge/docs-grow--codes--packages.github.io-blue)](https://grow-codes-packages.github.io/datadog-logs/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [![npm](https://img.shields.io/npm/v/@grow-codes/datadog-logs)](https://www.npmjs.com/package/@grow-codes/datadog-logs) | [![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-typescript.json)](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml) |
| Rust | [![crates.io](https://img.shields.io/crates/v/grow-datadog-logs)](https://crates.io/crates/grow-datadog-logs) [![docs.rs](https://img.shields.io/docsrs/grow-datadog-logs)](https://docs.rs/grow-datadog-logs) | [![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-rust.json)](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml) |
| Go | [![Go Reference](https://pkg.go.dev/badge/github.com/grow-codes-packages/datadog-logs/go.svg)](https://pkg.go.dev/github.com/grow-codes-packages/datadog-logs/go) | [![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-go.json)](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml) |
| Python | [![PyPI](https://img.shields.io/pypi/v/grow-datadog-logs)](https://pypi.org/project/grow-datadog-logs/) | [![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-python.json)](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml) |
| Java | [![Maven Central](https://img.shields.io/maven-central/v/codes.grow.packages/datadog-logs)](https://central.sonatype.com/artifact/codes.grow.packages/datadog-logs) | [![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-java.json)](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml) |

Open-source client libraries for [Datadog Log Management](#trademarks-and-disclaimer) in
**TypeScript, Rust, Go, Python and Java**: one API, five idiomatic packages, tested in Docker against
Datadog's own intake emulator.

- **Typed client** with timeouts, retries with jitter, `Retry-After` (and Datadog's
  `X-RateLimit-Reset`), cancellation and one error model (`DatadogLogsError` with canonical codes)
  in every language.
- **Keys stay secret**: the API key and the application key are redacted in `toString`, logs, hooks
  and error messages. The application key is only ever sent to the query API.
- **Logs intake** (`POST /api/v2/logs`): gzip, batches of at most 1,000 entries and 5 MB, records
  over 1 MB truncated (with `grow.truncated=true`), Datadog reserved attributes (`ddsource`, `ddtags`,
  `hostname`, `service`, `status`, `dd.trace_id`, `dd.span_id`) and every Datadog site.
- **Logging**: a batching log client with back-pressure, a logger facade, redaction and adapters for
  the common logging frameworks of each language, with automatic trace correlation.
- **Logs search** (needs an application key): `searchLogs` with cursor pagination, `aggregateLogs`,
  log indexes, a polling `tail` and the `waitForLog` test helper.

## Contents

- [Supported service version](#supported-service-version)
- [Install](#install)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Function overview](#function-overview)
- [Testing with Docker](#testing-with-docker)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)
- [Trademarks and disclaimer](#trademarks-and-disclaimer)
- [About Grow Codes](#about-grow-codes)

## Supported service version

Datadog Log Management is a SaaS without versions; the SDK uses these public endpoints:

| Endpoint | Used by |
| - | - |
| `POST https://http-intake.logs.<site>/api/v2/logs` (Logs intake API v2) | log shipping |
| `GET https://api.<site>/api/v1/validate` | `health`, `ping`, `validateApiKey` |
| `POST https://api.<site>/api/v2/logs/events/search` (Logs Search API v2) | `searchLogs`, `iterateLogs`, `search`, `tail`, `waitForLog` |
| `POST https://api.<site>/api/v2/logs/analytics/aggregate` | `aggregateLogs` |
| `GET https://api.<site>/api/v1/logs/config/indexes` | `listIndexes`, `listStreams` |

Supported sites: `datadoghq.com` (US1, default), `us3.datadoghq.com`, `us5.datadoghq.com`,
`ap1.datadoghq.com`, `ap2.datadoghq.com`, `uk1.datadoghq.com`, `datadoghq.eu`, `ddog-gov.com` and
`us2.ddog-gov.com`.

The Docker suite runs against **`datadog/fakeintake:v79`** (pinned by digest), Datadog's Apache-2.0
test intake. The Grow Codes stack catalog lists Datadog as a managed service and pins no image, so
the repository pins the frozen `v79` tag. fakeintake records intake payloads but has no search
engine: the search, aggregate and index functions are proven with recorded Datadog fixtures in unit
tests and with wire stubs in Docker. A live job against a Datadog sandbox organisation is planned;
see [Compatibility](#compatibility).

## Install

| Language | Package | Install |
| - | - | - |
| TypeScript (Node.js ≥ 22.12) | `@grow-codes/datadog-logs` | `npm install @grow-codes/datadog-logs` |
| Rust (MSRV 1.88) | `grow-datadog-logs` | `cargo add grow-datadog-logs` |
| Go (1.26+) | `github.com/grow-codes-packages/datadog-logs/go` | `go get github.com/grow-codes-packages/datadog-logs/go` |
| Python (≥ 3.10) | `grow-datadog-logs` | `pip install grow-datadog-logs` |
| Java (17+) | `codes.grow.packages:datadog-logs` | see below |

Maven:

```xml
<dependency>
  <groupId>codes.grow.packages</groupId>
  <artifactId>datadog-logs</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("codes.grow.packages:datadog-logs:0.1.0")
```

## Quickstart

Every package reads its configuration from the environment or from an explicit config object. Set
your API key and site (and, to read logs back, an application key with `logs_read_data`):

```sh
export DD_API_KEY=...          # never commit real keys
export DD_SITE=datadoghq.eu    # default datadoghq.com
export DD_APP_KEY=...          # optional, only for search/aggregate/indexes
```

The examples validate the key, ship two log records through the batching log client and find one
of them again with the search API.

### TypeScript

```ts
import { DatadogLogsClient } from "@grow-codes/datadog-logs";

const client = DatadogLogsClient.fromEnv({ service: "checkout" });
try {
  console.log("key valid:", await client.validateApiKey());

  const logs = client.logClient();
  const log = logs.logger("payments", { run: "run-7f3a" });
  log.info("checkout started run-7f3a");
  log.error("payment failed run-7f3a", { orderId: 42 });
  await logs.flush();

  const query = client.query(); // throws ConfigError without an application key
  const found = await query.waitForLog('"payment failed run-7f3a"', { timeoutMs: 120_000 });
  console.log(found.severityText, found.body);
} finally {
  await client.close(); // flushes every log client
}
```

### Rust

```rust
use std::time::Duration;

use grow_datadog_logs::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
use grow_datadog_logs::query::DEFAULT_QUERY_POLL_INTERVAL;
use grow_datadog_logs::{DatadogLogsClient, LogClientOptions};

#[tokio::main]
async fn main() -> grow_datadog_logs::Result<()> {
    let client = DatadogLogsClient::from_env()?;
    println!("key valid: {}", client.validate_api_key().await?);

    let logs = client.log_client(LogClientOptions::default())?;
    let log = logs.logger(Some("payments"), attrs! { "run" => "run-7f3a" });
    log.info("checkout started run-7f3a", attrs! {});
    log.error("payment failed run-7f3a", attrs! { "orderId" => 42 });
    logs.flush(Some(Duration::from_secs(30))).await?;

    let query = client.query()?; // CONFIG error without an application key
    let found = query
        .wait_for_log(
            "\"payment failed run-7f3a\"",
            WaitOptions::default()
                .timeout(Duration::from_secs(120))
                .interval(DEFAULT_QUERY_POLL_INTERVAL),
        )
        .await?;
    println!("{} {}", found.severity, found.body);
    client.close().await; // flushes every log client
    Ok(())
}
```

### Go

```go
package main

import (
	"context"
	"fmt"
	"log"
	"time"

	datadoglogs "github.com/grow-codes-packages/datadog-logs/go"
)

func main() {
	ctx := context.Background()
	client, err := datadoglogs.NewClientFromEnv()
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close() // flushes every log client

	valid, err := client.ValidateAPIKey(ctx)
	fmt.Println("key valid:", valid, err)

	logs, err := client.NewLogClient(datadoglogs.LogClientOptions{})
	if err != nil {
		log.Fatal(err)
	}
	logger := logs.Logger("payments", map[string]datadoglogs.AttrValue{"run": "run-7f3a"})
	logger.Info(ctx, "checkout started run-7f3a")
	logger.Error(ctx, "payment failed run-7f3a", "orderId", 42)
	if err := logs.Flush(ctx); err != nil {
		log.Fatal(err)
	}

	query, err := client.Query() // ErrConfig without an application key
	if err != nil {
		log.Fatal(err)
	}
	rec, err := query.WaitForLog(ctx, `"payment failed run-7f3a"`,
		datadoglogs.WaitForLogOptions{Timeout: 2 * time.Minute})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(rec.SeverityText, rec.Body)
}
```

### Python

```python
from grow_datadog_logs import DatadogLogsClient

with DatadogLogsClient.from_env(service="checkout") as client:  # close() flushes log clients
    print("key valid:", client.validate_api_key())

    logs = client.log_client()
    log = logs.logger("payments", {"run": "run-7f3a"})
    log.info("checkout started run-7f3a")
    log.error("payment failed run-7f3a", {"orderId": 42})
    logs.flush(30)

    query = client.query()  # ConfigError without an application key
    found = query.wait_for_log('"payment failed run-7f3a"', timeout_s=120)
    print(found.severity_text, found.body)
```

Async code uses `AsyncDatadogLogsClient` with the same methods (`async with`,
`await client.validate_api_key()`, `await query.wait_for_log(...)`).

### Java

```java
import codes.grow.packages.datadoglogs.DatadogLogClient;
import codes.grow.packages.datadoglogs.DatadogLogsClient;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.Logger;
import codes.grow.packages.datadoglogs.logging.WaitForLogOptions;
import java.time.Duration;
import java.util.Map;

try (DatadogLogsClient client = DatadogLogsClient.fromEnv()) { // close() flushes log clients
    System.out.println("key valid: " + client.validateApiKey());

    DatadogLogClient logs = client.logClient();
    Logger log = logs.logger("payments", Map.of("run", "run-7f3a"));
    log.info("checkout started run-7f3a", Map.of());
    log.error("payment failed run-7f3a", Map.of("orderId", 42));
    logs.flush();

    LogRecord found = client.query() // ConfigException without an application key
        .waitForLog("\"payment failed run-7f3a\"",
            WaitForLogOptions.of(Duration.ofMinutes(2), Duration.ofSeconds(5)));
    System.out.println(found.severityText() + " " + found.body());
}
```

More complete programs (hooks, framework adapters, aggregations, async clients) live in
[examples/](examples/), one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_DATADOG_LOGS_*` variable > Datadog variable (`DD_*`) > default.** A present but invalid value
raises a `ConfigError` that names the variable (never its value).

| Setting | Environment variables (in order) | Default |
| - | - | - |
| `apiKey` (secret, **required**) — header `DD-API-KEY` | `GROW_DATADOG_LOGS_API_KEY`, `DD_API_KEY` | none |
| `appKey` (secret) — header `DD-APPLICATION-KEY`, query API only | `GROW_DATADOG_LOGS_APP_KEY`, `DD_APP_KEY`, `DD_APPLICATION_KEY` | none |
| `site` | `GROW_DATADOG_LOGS_SITE`, `DD_SITE` | `datadoghq.com` |
| `url` — one base URL for intake and API (proxies, fakeintake) | `GROW_DATADOG_LOGS_URL` | derived from `site` |
| `intakeUrl` | `GROW_DATADOG_LOGS_INTAKE_URL` | `url`, else `https://http-intake.logs.<site>` |
| `apiUrl` | `GROW_DATADOG_LOGS_API_URL` | `url`, else `https://api.<site>` |
| `service` — default `service` of every log | `GROW_DATADOG_LOGS_SERVICE`, `DD_SERVICE` | none (`service.name` of the record resource wins) |
| `env` — tag `env:<value>` | `GROW_DATADOG_LOGS_ENV`, `DD_ENV` | none |
| `version` — tag `version:<value>` | `GROW_DATADOG_LOGS_VERSION`, `DD_VERSION` | none |
| `tags` — extra `key:value` tags (comma or space separated) | `GROW_DATADOG_LOGS_TAGS`, `DD_TAGS` | none |
| `hostname` | `GROW_DATADOG_LOGS_HOSTNAME`, `DD_HOSTNAME` | none |
| `source` — `ddsource` | `GROW_DATADOG_LOGS_SOURCE` | `grow-sdk` |
| `connectTimeout` | `GROW_DATADOG_LOGS_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_DATADOG_LOGS_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_DATADOG_LOGS_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_DATADOG_LOGS_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_DATADOG_LOGS_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_DATADOG_LOGS_CLIENT_CERT` / `GROW_DATADOG_LOGS_CLIENT_KEY` | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_DATADOG_LOGS_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_DATADOG_LOGS_PROXY` | none (environment proxies are ignored) |
| `headers`, `userAgentSuffix`, `hooks`, `logger` | — | none |

Rules that are the same in every language:

- An unknown `site` is a `ConfigError` unless both `intakeUrl` and `apiUrl` (or `url`) are set.
  Sites are matched case-insensitively; use the API site (`datadoghq.eu`), not the UI host.
- `ddtags` = `env:<env>`, `version:<version>`, then `tags`; duplicates are dropped and whitespace or
  commas inside one tag become `_`.
- `DD_TAGS` follows the Datadog Agent convention: items separated by commas and/or spaces.

Durations accept milliseconds (`1500`) or units (`500ms`, `10s`, `1m30s`); booleans accept
`true/false/1/0/yes/no/on/off`. Field names follow each language's convention (`api_key` in Python
and Rust, `APIKey` in Go). The full reference is in
[docs/configuration.md](docs/configuration.md).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`searchLogs` in
TypeScript and Java, `search_logs` in Python and Rust, `SearchLogs` in Go). Every function has a
page with all five languages in the
[documentation](https://grow-codes-packages.github.io/datadog-logs/functions/).

### Client and status

| Function | Description |
| - | - |
| `fromEnv()` / constructor / builder | build from `GROW_DATADOG_LOGS_*` / `DD_*` or an explicit config; `CONFIG` without an API key |
| `health()` | `GET /api/v1/validate`; any answer means reachable, 401/403 → `healthy=false`, `status="unauthorized"`; never throws for an unhealthy or unreachable service |
| `ping()` | authenticated `GET /api/v1/validate`; returns the latency, 403 → `AUTHENTICATION` |
| `validateApiKey()` | `true` for `{"valid": true}`, `false` for 403 |
| `serverVersion()` | always `UNSUPPORTED` (Datadog reports no version) |
| `close()` | idempotent; flushes every log client and stops every tail; later calls raise `CANCELLED` |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.validateApiKey()` | `client.validate_api_key().await?` | `client.ValidateAPIKey(ctx)` | `client.validate_api_key()` | `client.validateApiKey()` |

### Logs intake (`client.exporter`)

| Function | Description |
| - | - |
| `export(records, options)` | maps `LogRecord`s to Datadog entries and posts them (gzip by default); returns `{accepted, rejected, error}` and never throws for service errors |
| site resolver (`resolveSite`) | `datadoghq.eu` → `https://http-intake.logs.datadoghq.eu` and `https://api.datadoghq.eu` |
| `parseTags(value)` | `DD_TAGS` parsing |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.exporter.export(records, { gzip: true })` | `client.exporter().export(&records, ExportOptions::new(true)).await?` | `client.Exporter().Export(ctx, records, datadoglogs.ExportOptions{Gzip: true})` | `client.exporter.export(records)` | `client.exporter().export(records, new ExportOptions(true, Duration.ofSeconds(30))).join()` |

### Log client (category `LogClient`)

| Function | Description |
| - | - |
| `logClient(options)` | batching `LogClient`: `send`, `sendBatch`, `flush`, `stats`, `logger(name, attributes)`, `close`; the resource defaults to the configured `service`, `version`, `env`, `hostname` |
| framework adapters | pino and winston (TypeScript), `tracing` layer and `log` bridge (Rust), `slog.Handler` and `zapcore.Core` (Go), `logging.Handler` and structlog processor (Python), Logback, Log4j2 and JUL (Java) |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `client.logClient().logger("api").info("hi")` | `client.log_client(LogClientOptions::default())?` | `client.NewLogClient(datadoglogs.LogClientOptions{})` | `client.log_client().logger("api").info("hi")` | `client.logClient().logger("api").info("hi", Map.of())` |

### Logs search (`client.query()`, needs an application key)

| Function | Description |
| - | - |
| `searchLogs({query, from, to, limit, sort, indexes, storageTier, cursor})` | one page of `POST /api/v2/logs/events/search` (default `*`, last 15 min, 100 events, newest first) with `nextCursor` |
| `iterateLogs(request)` | every matching event across pages (cursor pagination, loop guard) |
| `aggregateLogs(compute, groupBy, filter)` | `POST /api/v2/logs/analytics/aggregate`; results keyed `c0`, `c1`, … (compute type defaults to `total`) |
| `listIndexes()` | log indexes (needs `logs_read_config`) |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await query.searchLogs({ query: "service:checkout", limit: 50 })` | `query.search_logs(&SearchLogsRequest::new("service:checkout")).await?` | `query.SearchLogs(ctx, datadoglogs.SearchLogsRequest{Query: "service:checkout"})` | `query.search_logs("service:checkout", limit=50)` | `query.searchLogs(SearchLogsRequest.builder().query("service:checkout").build())` |

### Logs query (category `LogQueryClient`)

| Function | Description |
| - | - |
| `search(query, {start, end, limit, order, cursor})` | normalized `LogSearchResult` of one search page |
| `tail(query, onRecord, {start, interval})` | polling tail (Datadog Live Tail has no public API); default interval 5 s |
| `waitForLog(query, {timeout, interval})` | polls `search` until a record matches (default timeout 30 s, interval 5 s) |
| `listStreams()` | index names |
| `listLabels()` | always `UNSUPPORTED` (Datadog has no attribute listing API) |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await query.waitForLog('"run-7f3a"', { timeoutMs: 60_000 })` | `query.wait_for_log("\"run-7f3a\"", WaitOptions::default().interval(DEFAULT_QUERY_POLL_INTERVAL)).await?` | `query.WaitForLog(ctx, "\"run-7f3a\"", datadoglogs.WaitForLogOptions{})` | `query.wait_for_log('"run-7f3a"', timeout_s=60)` | `query.waitForLog("\"run-7f3a\"")` |

<!-- /service:function-overview -->

## Testing with Docker

The integration suite runs in Docker against `datadog/fakeintake:v79`, Datadog's own test intake.
Each language package is packed and installed into a separate consumer project first, so the tests
use the package exactly as you would.

```sh
make test-docker                     # all five languages
make test-docker LANGS="go python"   # a subset
```

The suite ships logs through every intake path (exporter, log client, logger, framework adapters),
reads the captured payloads back from fakeintake and asserts them field by field (reserved
attributes, trace ids, truncation, batch limits, gzip). Error handling is checked with injected
intake answers (400, 401, 403, 408, 413, 429, 500, 503). fakeintake has no search engine, so the
search, aggregate, index, tail and `waitForLog` calls run against wire stubs, and the request the
SDK sent is asserted from the capture.

No host ports are published and every run uses its own Compose project, so runs never collide; the
stack is always removed afterwards (`docker compose down -v`). Unit tests need no Docker:
`make test` or `make coverage`.

Live checks against a real Datadog organisation (the search, aggregate, index and key functions
fakeintake cannot serve) live in `live/` and run weekly in `.github/workflows/live.yml` once the
`DATADOG_API_KEY` / `DATADOG_APP_KEY` secrets exist. Run them locally with the same variables:

```sh
DATADOG_API_KEY=… DATADOG_APP_KEY=… DATADOG_SITE=datadoghq.eu make -C live live
```

Without the keys the suite prints `skipped: no credentials` and exits 0. Details:
[docs/compatibility.md](docs/compatibility.md#live-checks).

## Compatibility

| | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Runtime | Node.js ≥ 22.12, ESM + CJS | stable, MSRV 1.88 | 1.26, 1.27 | 3.10 – 3.14 | 17, 21, 25 |
| Async | `Promise` + `AbortSignal` | `async` (tokio), `blocking` feature | `context.Context` | sync + `asyncio` | sync + `CompletableFuture` |
| Datadog intake (fakeintake v79) | yes | yes | yes | yes | yes |
| Datadog search, aggregate, indexes | unit + wire stub | unit + wire stub | unit + wire stub | unit + wire stub | unit + wire stub |

The Logs Search, Aggregate and Indexes APIs exist only in the Datadog SaaS: they are covered by unit
tests with recorded Datadog fixtures and by wire stubs in Docker. Live checks against a Datadog
sandbox are pending sandbox keys. Language differences (for example the poll interval defaults of
the Rust and Java option types) are listed in [docs/compatibility.md](docs/compatibility.md).

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[AGENTS.md](AGENTS.md) first. All five languages keep the same API; a change lands in all of them
together. Parts of this repository are generated by
[sdk-kit](https://github.com/grow-codes-packages/sdk-kit) and changed there.

## Security

Please report vulnerabilities privately to [opensource@grow.codes](mailto:opensource@grow.codes).
See [SECURITY.md](SECURITY.md).

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.

## Trademarks and disclaimer

This is an independent, community-maintained project. It is **not affiliated with, endorsed or
sponsored by Datadog, Inc.** Datadog and Datadog Log Management are trademarks of Datadog, Inc.;
they are used here only to name the service this SDK connects to. Datadog is a proprietary SaaS;
this SDK contains no Datadog code and talks to the service over its public HTTP API. The
`datadog/fakeintake` image (Apache-2.0) is used only by the test suite and is not shipped in any
package.

The software is provided "AS IS", without warranties or conditions of any kind. Grow Codes is not
responsible for any damage, data loss, security incident, outage or cost (including Datadog
ingestion or retention charges) that results from using it. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).

## About Grow Codes

This SDK is built and maintained by [Grow Codes](https://grow.codes). Grow Codes publishes the
client libraries it builds as open source in the
[grow-codes-packages](https://github.com/grow-codes-packages) organisation, generated from one
shared kit so that every service and every language behaves the same way. Learn more at
[grow.codes](https://grow.codes).
