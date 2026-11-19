# TypeScript integration tests

A consumer project: it installs the packed `@grow-codes/datadog-logs` tarball (never the source tree)
and runs the integration and conformance tests with `node --test` against `datadog/fakeintake` (ingest round trips read back from its raw capture; the query API
is exercised through canned official-shape responses installed as fakeintake overrides).

```sh
cd typescript && make pack            # writes dist/grow-codes-datadog-logs-<version>.tgz
cd integration && npm run install-package
GROW_DATADOG_LOGS_URL=http://127.0.0.1:<port> FAKEINTAKE_URL=http://127.0.0.1:<port> DD_API_KEY=test DD_APP_KEY=test npm test
```

In CI and locally with Docker this runs inside `docker/Dockerfile.typescript`
(`make test-docker LANGS=typescript` from the repository root), which reads `/bootstrap/env`.
