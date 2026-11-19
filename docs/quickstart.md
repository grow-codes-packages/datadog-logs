# Quickstart

Ship logs to Datadog Log Management and read one of them back. The client reads `DD_API_KEY`,
`DD_SITE` and `DD_APP_KEY` (or the `GROW_DATADOG_LOGS_*` names listed in
[Configuration](configuration.md)), or takes the values in code.

```sh
export DD_API_KEY=...          # never commit real keys
export DD_SITE=datadoghq.eu    # default datadoghq.com
export DD_APP_KEY=...          # optional: search, aggregate and indexes need it (logs_read_data)
```

Each example validates the key, sends two records through the batching log client (gzip, at most
1,000 entries / 5 MB per request), flushes, and waits until the search API returns one of them.
Without an application key, stop after the flush.

=== "TypeScript"

    ```ts
    import { DatadogLogsClient } from "@grow-codes/datadog-logs";

    const client = DatadogLogsClient.fromEnv({ service: "checkout" });
    try {
      console.log("key valid:", await client.validateApiKey());

      const logs = client.logClient();
      const log = logs.logger("payments", { run: "run-7f3a" });
      log.info("checkout started run-7f3a");
      log.error("payment failed run-7f3a", { orderId: 42 });
      await logs.flush();

      const query = client.query(); // throws ConfigError without an application key
      const found = await query.waitForLog('"payment failed run-7f3a"', { timeoutMs: 120_000 });
      console.log(found.severityText, found.body);
    } finally {
      await client.close(); // flushes every log client
    }
    ```

=== "Rust"

    ```rust
    use std::time::Duration;

    use grow_datadog_logs::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
    use grow_datadog_logs::query::DEFAULT_QUERY_POLL_INTERVAL;
    use grow_datadog_logs::{DatadogLogsClient, LogClientOptions};

    #[tokio::main]
    async fn main() -> grow_datadog_logs::Result<()> {
        let client = DatadogLogsClient::from_env()?;
        println!("key valid: {}", client.validate_api_key().await?);

        let logs = client.log_client(LogClientOptions::default())?;
        let log = logs.logger(Some("payments"), attrs! { "run" => "run-7f3a" });
        log.info("checkout started run-7f3a", attrs! {});
        log.error("payment failed run-7f3a", attrs! { "orderId" => 42 });
        logs.flush(Some(Duration::from_secs(30))).await?;

        let query = client.query()?; // CONFIG error without an application key
        let found = query
            .wait_for_log(
                "\"payment failed run-7f3a\"",
                WaitOptions::default()
                    .timeout(Duration::from_secs(120))
                    .interval(DEFAULT_QUERY_POLL_INTERVAL),
            )
            .await?;
        println!("{} {}", found.severity, found.body);
        client.close().await; // flushes every log client
        Ok(())
    }
    ```

=== "Go"

    ```go
    package main

    import (
    	"context"
    	"fmt"
    	"log"
    	"time"

    	datadoglogs "github.com/grow-codes-packages/datadog-logs/go"
    )

    func main() {
    	ctx := context.Background()
    	client, err := datadoglogs.NewClientFromEnv()
    	if err != nil {
    		log.Fatal(err)
    	}
    	defer client.Close() // flushes every log client

    	valid, err := client.ValidateAPIKey(ctx)
    	fmt.Println("key valid:", valid, err)

    	logs, err := client.NewLogClient(datadoglogs.LogClientOptions{})
    	if err != nil {
    		log.Fatal(err)
    	}
    	logger := logs.Logger("payments", map[string]datadoglogs.AttrValue{"run": "run-7f3a"})
    	logger.Info(ctx, "checkout started run-7f3a")
    	logger.Error(ctx, "payment failed run-7f3a", "orderId", 42)
    	if err := logs.Flush(ctx); err != nil {
    		log.Fatal(err)
    	}

    	query, err := client.Query() // ErrConfig without an application key
    	if err != nil {
    		log.Fatal(err)
    	}
    	rec, err := query.WaitForLog(ctx, `"payment failed run-7f3a"`,
    		datadoglogs.WaitForLogOptions{Timeout: 2 * time.Minute})
    	if err != nil {
    		log.Fatal(err)
    	}
    	fmt.Println(rec.SeverityText, rec.Body)
    }
    ```

=== "Python"

    ```python
    from grow_datadog_logs import DatadogLogsClient

    with DatadogLogsClient.from_env(service="checkout") as client:  # close() flushes log clients
        print("key valid:", client.validate_api_key())

        logs = client.log_client()
        log = logs.logger("payments", {"run": "run-7f3a"})
        log.info("checkout started run-7f3a")
        log.error("payment failed run-7f3a", {"orderId": 42})
        logs.flush(30)

        query = client.query()  # ConfigError without an application key
        found = query.wait_for_log('"payment failed run-7f3a"', timeout_s=120)
        print(found.severity_text, found.body)
    ```

    Async code uses `AsyncDatadogLogsClient` with the same methods:

    ```python
    import asyncio
    from grow_datadog_logs import AsyncDatadogLogsClient

    async def main() -> None:
        async with AsyncDatadogLogsClient.from_env() as client:
            print(await client.validate_api_key())
            logs = client.log_client()
            logs.logger("payments").info("hello from asyncio")
            await logs.flush(30)

    asyncio.run(main())
    ```

=== "Java"

    ```java
    import codes.grow.packages.datadoglogs.DatadogLogClient;
    import codes.grow.packages.datadoglogs.DatadogLogsClient;
    import codes.grow.packages.datadoglogs.logging.LogRecord;
    import codes.grow.packages.datadoglogs.logging.Logger;
    import codes.grow.packages.datadoglogs.logging.WaitForLogOptions;
    import java.time.Duration;
    import java.util.Map;

    try (DatadogLogsClient client = DatadogLogsClient.fromEnv()) { // close() flushes log clients
        System.out.println("key valid: " + client.validateApiKey());

        DatadogLogClient logs = client.logClient();
        Logger log = logs.logger("payments", Map.of("run", "run-7f3a"));
        log.info("checkout started run-7f3a", Map.of());
        log.error("payment failed run-7f3a", Map.of("orderId", 42));
        logs.flush();

        LogRecord found = client.query() // ConfigException without an application key
            .waitForLog("\"payment failed run-7f3a\"",
                WaitForLogOptions.of(Duration.ofMinutes(2), Duration.ofSeconds(5)));
        System.out.println(found.severityText() + " " + found.body());
    }
    ```

## Against the Docker fakeintake

Point every endpoint at a local [fakeintake](docker-testing.md) to try the intake without a
Datadog account (fakeintake accepts any key and has no search engine):

```sh
export GROW_DATADOG_LOGS_URL=http://127.0.0.1:<port>
export DD_API_KEY=test
```

## Next steps

- [Configuration](configuration.md): sites, keys, tags, timeouts, retries, TLS, proxy.
- [Functions](functions.md): the Datadog Log Management API of this SDK.
- [Logging](logging.md): batching, back-pressure, redaction and framework adapters.
- [Error handling](errors.md): error codes and retries.
