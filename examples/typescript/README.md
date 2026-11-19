# TypeScript example

```sh
(cd ../../typescript && npm install && npm run build)
npm install
DD_API_KEY=... DD_SITE=datadoghq.com DD_APP_KEY=... npm start
```

It validates the API key, ships logs through the batching log client (Logger facade, a child
logger and a record with trace ids), and — when `DD_APP_KEY` is set — waits for them in Datadog and
reads them back with `waitForLog`, `searchLogs`, `iterateLogs`, `aggregateLogs` and `listStreams`.

Without an application key only the ingest part runs. To try it against the Docker fakeintake, set
`GROW_DATADOG_LOGS_URL=http://127.0.0.1:<port>` and any `DD_API_KEY`.

Replace the `file:` dependency in `package.json` with a version (`npm install @grow-codes/datadog-logs`)
to run it against the published package.
