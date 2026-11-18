# @grow-codes/datadog-logs

TypeScript SDK for [Datadog Log Management](https://grow-codes-packages.github.io/datadog-logs/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/datadog-logs
```

```ts
import { DatadogLogsClient } from "@grow-codes/datadog-logs";

// Reads GROW_DATADOG_LOGS_URL, GROW_DATADOG_LOGS_TOKEN, timeouts, TLS and proxy settings.
const client = DatadogLogsClient.fromEnv();
try {
  console.log(await client.health()); // { healthy: true, status: "ok", details: {} }
  console.log(`ping: ${(await client.ping()).toFixed(1)} ms`);
} finally {
  await client.close();
}
```

Every error is a `DatadogLogsError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/datadog-logs/testing`.

Documentation: https://grow-codes-packages.github.io/datadog-logs/ · Licence: Apache-2.0.
Datadog Log Management is a trademark of Datadog, Inc.; this package is not affiliated with or endorsed by it.
