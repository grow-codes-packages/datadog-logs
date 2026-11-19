# Java examples

Install the package into your local Maven repository, point it at a running Datadog Log Management and run an
example:

```bash
make -C ../../java pack            # builds and installs codes.grow.packages:datadog-logs
export GROW_DATADOG_LOGS_URL=http://127.0.0.1:80
export DD_API_KEY=...                # any value for fakeintake; DD_APP_KEY for the query API
mvn -q compile exec:java                                                  # BasicExample
mvn -q compile exec:java -Dexec.mainClass=codes.grow.packages.datadoglogs.examples.AsyncExample
```

Against the published package, set `-Dsdk.version=<version>` (or edit `pom.xml`) instead of
installing it locally.

| Class | Shows |
| - | - |
| `BasicExample` | API key check, log client + Logger, search / iterate / aggregate / indexes |
| `AsyncExample` | `…Async` methods, cancellation, non-blocking flush |
| `HooksAndLoggingExample` | request hooks, a logger, retries and timeouts |
