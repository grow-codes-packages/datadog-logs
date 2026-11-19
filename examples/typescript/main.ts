// Datadog Log Management with the TypeScript SDK: configuration from the environment, API key
// check, shipping logs through the batching log client (Logger facade + direct records), and —
// when an application key is set — reading them back with the search, aggregate and index APIs.
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   DD_API_KEY=... DD_SITE=datadoghq.eu [DD_APP_KEY=...] npm start
//   # against the Docker fakeintake: GROW_DATADOG_LOGS_URL=http://127.0.0.1:<port> DD_API_KEY=test npm start

import { DatadogLogsClient, DatadogLogsError, Severity } from "@grow-codes/datadog-logs";

const client = DatadogLogsClient.fromEnv({
  service: process.env.DD_SERVICE ?? "grow-sdk-example",
  // Warnings only; the default logger is silent.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
});
console.log(`config: ${client.config}`); // keys print as [REDACTED]

const marker = `example-${Date.now().toString(36)}`;
try {
  console.log("health:", await client.health()); // never throws for an unreachable service
  console.log("API key valid:", await client.validateApiKey());

  // 1. Ship logs: a batching LogClient (gzip, ≤ 1000 entries / 5 MB per request, background flush).
  const logs = client.logClient({
    resource: { "deployment.environment": "example" },
    batch: { onError: (err) => console.error("export failed:", err) },
  });
  const log = logs.logger("example", { marker });
  log.info(`hello from the Grow Codes SDK ${marker}`, { user: { id: 42 }, password: "never-sent" });
  log.child({ step: "checkout" }).warn(`payment slow ${marker}`, { durationMs: 1234 });
  logs.send({
    message: `traced ${marker}`,
    severity: Severity.Error,
    traceId: "4bf92f3577b34da6a3ce929d0e0e4736", // sent as dd.trace_id (decimal, lower 64 bits)
    spanId: "00f067aa0ba902b7",
  });
  await logs.flush();
  console.log("sent:", logs.stats());

  // 2. Read back (needs DD_APP_KEY with logs_read_data; Datadog indexes within seconds).
  if (client.config.appKey !== undefined) {
    const query = client.query();
    const found = await query.waitForLog(`"${marker}"`, { timeoutMs: 120_000 });
    console.log("found:", found.body, found.severityText);

    const page = await query.searchLogs({ query: `"${marker}"`, from: "now-15m", limit: 10 });
    console.log(`search: ${page.events.length} events, next cursor: ${page.nextCursor ?? "none"}`);

    for await (const event of query.iterateLogs({ query: `"${marker}"`, limit: 100 })) {
      console.log(" -", event.timestamp?.toISOString(), event.status, event.message);
    }

    const agg = await query.aggregateLogs({ aggregation: "count" }, [{ facet: "status" }], {
      query: `"${marker}"`,
    });
    console.log("count by status:", agg.buckets);

    try {
      console.log("indexes:", await query.listStreams()); // needs logs_read_config
    } catch (err) {
      console.log("indexes: not readable with this key", (err as Error).message);
    }
  } else {
    console.log("set DD_APP_KEY to read the logs back");
  }
} catch (err) {
  if (err instanceof DatadogLogsError) {
    // Canonical code + HTTP status; the message never contains keys.
    console.error(`${err.code}: ${err.message}`, { httpStatus: err.httpStatus });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await client.close(); // flushes every log client
}
