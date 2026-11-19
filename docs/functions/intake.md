# Logs intake

`client.exporter` is the Datadog `LogExporter` behind every log client. It maps SDK `LogRecord`s to
Datadog log entries and posts them to `POST https://http-intake.logs.<site>/api/v2/logs` with the
`DD-API-KEY` header. Most code uses the [log client](log-client.md); call the exporter directly to
send already-built records synchronously.

## `export(records, options)`

- Encodes each record (below), splits the batch into requests of at most **1,000 entries and
  5,000,000 bytes** (uncompressed JSON array) and gzips each request unless `gzip` is off.
- A record whose JSON is larger than **1 MB** gets its `message` truncated to fit and the attribute
  `grow.truncated=true`; a record whose attributes alone exceed 1 MB is dropped and counted in
  `rejected` with a `VALIDATION` error.
- Returns `{accepted, rejected, error}`. It never throws for service errors: a failing request
  stops the export and the remaining records count as rejected.
- Retried on 408, 429, 500, 502, 503, 504 and network errors (a retry after a lost answer can store
  a batch twice; Datadog has no idempotency key).

## Entry mapping

| Datadog field | From |
| - | - |
| `message` | `body` |
| `timestamp` | `timestamp` as epoch milliseconds |
| `status` | `severityText` lower-cased, else the severity name (`info`, `warn`, …) |
| `service` | resource `service.name`, else config `service` |
| `hostname` | resource `host.name`, else config `hostname` |
| `ddsource` | config `source` (default `grow-sdk`) |
| `ddtags` | `env:` (resource `deployment.environment` or config `env`), `version:` (resource `service.version` or config `version`), then config `tags` |
| `dd.trace_id` / `dd.span_id` | decimal string of the lower 64 bits of `traceId` / `spanId` |
| `otel.trace_id` / `otel.span_id` | the full ids, lower-case hex |
| other keys | the record attributes; a user attribute named like a reserved key is sent as `attr.<name>` |

=== "TypeScript"

    ```ts
    import { Severity } from "@grow-codes/datadog-logs";

    const res = await client.exporter.export(
      [{
        timestamp: new Date(), severity: Severity.Info, severityText: "INFO",
        body: "order paid", attributes: { orderId: 42 }, resource: { "service.name": "checkout" },
      }],
      { gzip: true },
    );
    console.log(res.accepted, res.rejected, res.error?.code);
    ```

=== "Rust"

    ```rust
    use grow_datadog_logs::logging::{ExportOptions, LogExporter, LogRecord, Severity};

    let rec = LogRecord::new(Severity::Info, "order paid").with_attribute("orderId", 42);
    let res = client.exporter().export(&[rec], ExportOptions::new(true)).await?;
    println!("{} {} {:?}", res.accepted, res.rejected, res.error);
    ```

=== "Go"

    ```go
    res, err := client.Exporter().Export(ctx, []datadoglogs.LogRecord{{
        Body:       "order paid",
        Severity:   datadoglogs.SeverityInfo,
        Attributes: map[string]datadoglogs.AttrValue{"orderId": 42},
    }}, datadoglogs.ExportOptions{Gzip: true})
    fmt.Println(res.Accepted, res.Rejected, err)
    ```

=== "Python"

    ```python
    from grow_datadog_logs import LogRecord, Severity

    res = client.exporter.export(
        [LogRecord(body="order paid", severity=Severity.INFO, attributes={"orderId": 42})]
    )
    print(res.accepted, res.rejected, res.error)
    ```

=== "Java"

    ```java
    LogRecord rec = LogRecord.builder()
        .body("order paid")
        .severity(Severity.INFO)
        .attributes(Map.of("orderId", 42))
        .build();
    ExportResult res = client.exporter()
        .export(List.of(rec), new ExportOptions(true, Duration.ofSeconds(30)))
        .join();
    System.out.println(res.accepted() + " " + res.rejected() + " " + res.error());
    ```
