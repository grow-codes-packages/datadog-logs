# Datadog Log Management SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/datadog-logs/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **Datadog Log Management** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against a real
`datadog/fakeintake (pinned tag) + optional live Datadog sandbox` in Docker.

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/datadog-logs`](https://www.npmjs.com/package/@grow-codes/datadog-logs) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-typescript.json) |
| Rust | [`grow-datadog-logs`](https://crates.io/crates/grow-datadog-logs) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/datadog-logs/go`](https://pkg.go.dev/github.com/grow-codes-packages/datadog-logs/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-go.json) |
| Python | [`grow-datadog-logs`](https://pypi.org/project/grow-datadog-logs/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-python.json) |
| Java | [`codes.grow.packages:datadog-logs`](https://central.sonatype.com/artifact/codes.grow.packages/datadog-logs) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-logs/badges/coverage-java.json) |

## What you get

- **Client** with health, authenticated ping and server version ([Client](client.md)).
- **Configuration** from code or `GROW_DATADOG_LOGS_*` / vendor environment variables, immutable
  and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language.
- **Typed errors** with one set of codes across languages ([Error handling](errors.md)).
- **Redaction everywhere**: credentials never appear in errors, logs, hooks or `toString`
  ([Redaction](redaction.md)).
- **Logging**: batching log client, logger facade, redaction processor, framework adapters and
  log search ([Logging](logging.md)).
- **Service functions** for the Datadog Log Management API ([Functions](functions.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Datadog Log Management is a trademark of
    Datadog, Inc.. This project is not affiliated with or endorsed by Datadog, Inc.. See
    [License & disclaimer](license.md).
