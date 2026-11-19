// Shared conformance scenarios (conformance/scenarios) against datadog/fakeintake.
//   core:    capabilities [] — "version" is not declared because Datadog reports no server version.
//   logging: capability "query" — the logging target uses the real DatadogLogClient (real intake
//            requests) and a fakeintake-backed query client that reads the captured payloads back,
//            so roundtrip/batch/redaction prove what actually reached the intake. Datadog's own
//            search API runs only in the live sandbox job.

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { DatadogLogsClient } from "@grow-codes/datadog-logs";
import { formatReport, runConformance } from "@grow-codes/datadog-logs/testing";
import { loadBootstrapEnv, scenariosDir } from "./env.ts";
import { FakeintakeLogQuery, restoreDefaults } from "./fakeintake.ts";

const clients: DatadogLogsClient[] = [];

function datadog(): DatadogLogsClient {
  const c = DatadogLogsClient.fromEnv({ retry: { maxRetries: 2, initialBackoffMs: 50 } });
  clients.push(c);
  return c;
}

before(async () => {
  loadBootstrapEnv();
  await restoreDefaults();
});

after(async () => {
  await Promise.all(clients.map((c) => c.close()));
});

test("conformance: core + logging", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["core", "logging"],
    factories: {
      core: () => datadog(),
      logging: (ctx) => ({
        client: datadog().logClient({
          batch: { flushIntervalMs: 200, ...(ctx.batchOptions ?? {}) },
          resource: { "service.name": "sdk-ts-conformance" },
        }),
        query: new FakeintakeLogQuery(),
      }),
    },
    capabilities: { core: [], logging: ["query"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
  assert.ok(report.passed >= 4);
});
