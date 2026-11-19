# @grow-codes/datadog-logs

TypeScript SDK for [Datadog Log Management](https://grow-codes-packages.github.io/datadog-logs/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/datadog-logs
```

```ts
import { DatadogLogsClient } from "@grow-codes/datadog-logs";

// Reads DD_API_KEY, DD_APP_KEY, DD_SITE, DD_SERVICE, DD_ENV, DD_VERSION, DD_TAGS (or the
// GROW_DATADOG_LOGS_* equivalents), timeouts, TLS and proxy settings.
const client = DatadogLogsClient.fromEnv();
try {
  console.log(await client.validateApiKey()); // true
  const logs = client.logClient({ resource: { "service.name": "checkout" } });
  logs.logger().info("user signed in", { user: { id: 42 } }); // batched, gzip, background flush

  // Query API (needs DD_APP_KEY): search, cursor iterator, aggregation, polling tail.
  const page = await client.query().searchLogs({ query: "service:checkout", from: "now-15m" });
  console.log(page.records.map((r) => r.body));
} finally {
  await client.close(); // flushes every log client
}
```

Every error is a `DatadogLogsError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/datadog-logs/testing`.

Documentation: https://grow-codes-packages.github.io/datadog-logs/ · Licence: Apache-2.0.
Datadog Log Management is a trademark of Datadog, Inc.; this package is not affiliated with or endorsed by it.
