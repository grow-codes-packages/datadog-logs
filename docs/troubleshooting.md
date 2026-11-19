# Troubleshooting

## The client cannot connect

- `health()` returns `healthy: false, status: "unreachable"`: the site or URL is wrong, or the
  network blocks it. Check `DD_SITE` (the API site such as `datadoghq.eu`, not `app.datadoghq.eu`)
  or `GROW_DATADOG_LOGS_URL` / `GROW_DATADOG_LOGS_INTAKE_URL` / `GROW_DATADOG_LOGS_API_URL`.
- Calls fail with `UNAVAILABLE` after several seconds: the client retried with backoff. Lower
  `maxRetries` or `totalTimeout` to fail faster.
- Behind a corporate proxy: `HTTP_PROXY`/`HTTPS_PROXY` are **ignored** on purpose. Set `proxy`
  (or `GROW_DATADOG_LOGS_PROXY`).

## `CONFIG` errors

The message names the setting or environment variable, never its value. Typical causes: no API key
(`DD_API_KEY` or `GROW_DATADOG_LOGS_API_KEY`), an unknown site, a URL without `http://` or
`https://`, a duration such as `10 sec` (use `10s` or `10000`), a client certificate without its
key. `client.query()` raises `CONFIG` when no application key is set (`DD_APP_KEY`).

## `AUTHENTICATION`

- `validateApiKey()` is the quickest key check: `false` means Datadog rejected the key.
- Datadog answers 403 for a missing or invalid key **and** for a key without the needed permission;
  the SDK maps every 403 to `AUTHENTICATION` (status 403 kept on the error).
- API and application keys belong to one site: a US1 key does not work on `datadoghq.eu`.
- The query API needs an application key with `logs_read_data`; `listIndexes`/`listStreams` also
  need `logs_read_config`.
- The SDK never prints keys, so `toString()` of the config shows `[REDACTED]` even when a key is
  set.

## Logs do not show up in Datadog

- The intake answers 202 before the logs are indexed; indexing usually takes seconds. Use
  `waitForLog` with a generous timeout in tests.
- Check that an index (and its exclusion filters) accepts the logs, and that the timestamp is not
  older than Datadog accepts (18 hours).
- `ddsource` defaults to `grow-sdk`; set `source` to route logs into an integration pipeline.
- Records over 1 MB are truncated (`grow.truncated=true`); a record whose attributes alone exceed
  1 MB is rejected with `VALIDATION` and counted in `rejected`.

## TLS errors

- Self-signed or private CA (for example a TLS-intercepting proxy): set `tls.caCertPath`
  (`GROW_DATADOG_LOGS_CA_CERT`).
- Mutual TLS: set both `tls.clientCertPath` and `tls.clientKeyPath`.
- `tls.insecureSkipVerify` only for local tests.

## `RATE_LIMITED`

Datadog asked the client to slow down; the search API in particular has a low rate limit. The
client waits for `Retry-After` (or `X-RateLimit-Reset`) up to `maxRetryAfter` (60 s); longer waits
fail at once with `retryAfter` set on the error so your code can schedule a later attempt. Keep
`tail` and `waitForLog` at the 5 s default interval or slower.

## `TIMEOUT`

`requestTimeout` (30 s) bounds one attempt and `totalTimeout` (120 s) the whole call including
retries. Raise them for slow operations, or pass a per-call deadline (signal, ctx, cancel token,
future timeout). `waitForLog` raises `TIMEOUT` when no record matched before its own timeout.

## See what the client does

Turn on the [logger](hooks.md#logger) or add [hooks](hooks.md#hooks): both see every attempt with
redacted headers and URLs.

## Docker integration tests fail

- Run one language: `make test-docker LANGS=go`.
- In CI, download the `compose-logs-<lang>` artifact of the failed job.
- Locally, the stack is removed after each run; rerun with Docker logs open
  (`docker compose -f docker/compose.yml logs -f`) in a second terminal.

## Still stuck?

Open an issue with the language, package version, Datadog site and a minimal reproduction (with
keys removed): <https://github.com/grow-codes-packages/datadog-logs/issues>.
