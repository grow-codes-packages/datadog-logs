# Functions

The Datadog Log Management API of this SDK. Every function exists in all five languages with the same
behaviour; this page shows each one with an example per language.

!!! info "Work in progress"
    The service functions are being added. Until then the client offers the shared surface:
    [`health()`, `ping()`, `serverVersion()`, `close()`](client.md).

## Overview

| Function | Description | Integration test |
| - | - | - |
| `health()` | unauthenticated liveness | `core.health` |
| `ping()` | authenticated round trip | `core.health` |
| `serverVersion()` | server version | `core.health` |

## Service configuration

| Setting | Default | Environment variables (precedence order) |
| - | - | - |
| `url` | `http://127.0.0.1:80` | `GROW_DATADOG_LOGS_URL` |
| `token` | none | `GROW_DATADOG_LOGS_TOKEN` |

Environment variables recognised by this SDK (explicit config values always win; `GROW_*` names
come before vendor names): `DD_API_KEY`, `DD_APP_KEY` (query only), `DD_SITE` (datadoghq.com, datadoghq.eu, us3/us5/ap1, ddog-gov.com), `DD_SERVICE`, `DD_ENV`, `DD_VERSION`, `DD_TAGS`, `GROW_DATADOG_LOGS_*`.
