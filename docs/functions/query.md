# Query, tail and waitForLog

`DatadogLogsQuery` also implements the logging category `LogQueryClient`, so code written against
the shared interface works with Datadog. Every method is built on the
[Logs Search API](search.md) and needs the application key.

| Function | Datadog behaviour |
| - | - |
| `search(query, {start, end, limit, order, cursor})` | one `searchLogs` page as a normalized `LogSearchResult` (`records`, `nextCursor`; `total` is never set because Datadog returns none). Default limit 100, newest first |
| `tail(query, onRecord, {start, interval})` | **polling** tail (Datadog Live Tail has no public API): searches every `interval` (default **5 s**) for logs from `start` (default now), oldest first, each log id once. `client.close()` stops every tail |
| `waitForLog(query, {timeout, interval, predicate})` | polls `search` until a record matches; default timeout 30 s, interval **5 s**; `AUTHENTICATION`/`VALIDATION` errors propagate at once, `TIMEOUT` at the deadline |
| `listStreams()` | the names of the log indexes (`listIndexes`, needs `logs_read_config`) |
| `listLabels()` | always `UNSUPPORTED`: Datadog has no public API that lists log attributes |

!!! note "Mind the search rate limit"
    The Logs Search API allows only a few requests per minute and per organisation. Keep the 5 s
    default interval (or slower). In Rust and Java the option types come from the shared
    interface and default to 1 s, so pass the Datadog interval explicitly
    (`DEFAULT_QUERY_POLL_INTERVAL` in Rust, `DatadogLogsQuery.DEFAULT_POLL_INTERVAL` in Java; see
    [Compatibility](../compatibility.md)).

=== "TypeScript"

    ```ts
    const query = client.query();
    const res = await query.search("service:checkout", { start: new Date(Date.now() - 3_600_000), limit: 20 });
    res.records.forEach((r) => console.log(r.severityText, r.body));

    const tail = query.tail("service:checkout status:error", (r) => console.log(r.body));
    const found = await query.waitForLog('"run-7f3a"', { timeoutMs: 120_000 });
    tail.stop();

    console.log(await query.listStreams());
    await query.listLabels().catch((e) => console.log(e.code)); // "UNSUPPORTED"
    ```

=== "Rust"

    ```rust
    use std::sync::Arc;
    use std::time::Duration;
    use grow_datadog_logs::logging::{LogQueryClient, SearchOptions, TailOptions, WaitOptions};
    use grow_datadog_logs::query::DEFAULT_QUERY_POLL_INTERVAL;

    let query = client.query()?;
    let res = query.search("service:checkout", SearchOptions::default().limit(20)).await?;

    let tail = query
        .tail(
            "service:checkout status:error",
            Arc::new(|r| println!("{}", r.body)),
            TailOptions { interval: DEFAULT_QUERY_POLL_INTERVAL, start: None },
        )
        .await?;
    let found = query
        .wait_for_log(
            "\"run-7f3a\"",
            WaitOptions::default()
                .timeout(Duration::from_secs(120))
                .interval(DEFAULT_QUERY_POLL_INTERVAL),
        )
        .await?;
    tail.stop();
    let indexes = query.list_streams().await?;
    ```

=== "Go"

    ```go
    query, err := client.Query()
    res, err := query.Search(ctx, "service:checkout", datadoglogs.SearchOptions{Limit: 20})

    tail, err := query.Tail(ctx, "service:checkout status:error",
        func(r datadoglogs.LogRecord) { fmt.Println(r.Body) }, datadoglogs.TailOptions{})
    rec, err := query.WaitForLog(ctx, `"run-7f3a"`, datadoglogs.WaitForLogOptions{Timeout: 2 * time.Minute})
    tail.Stop()

    streams, err := query.ListStreams(ctx)
    _, err = query.ListLabels(ctx) // errors.Is(err, datadoglogs.ErrUnsupported)
    ```

=== "Python"

    ```python
    query = client.query()
    res = query.search("service:checkout", limit=20)
    for r in res.records:
        print(r.severity_text, r.body)

    tail = query.tail("service:checkout status:error", lambda r: print(r.body))
    found = query.wait_for_log('"run-7f3a"', timeout_s=120)
    tail.stop()
    print(query.list_streams())
    ```

    The async client has the same methods (`await query.wait_for_log(...)`,
    `await query.tail(...)`).

=== "Java"

    ```java
    DatadogLogsQuery query = client.query();
    LogSearchResult res = query.search("service:checkout", SearchOptions.builder().limit(20).build());

    TailHandle tail = query.tail("service:checkout status:error",
        r -> System.out.println(r.body()),
        new TailOptions(Optional.empty(), DatadogLogsQuery.DEFAULT_POLL_INTERVAL, null));
    LogRecord found = query.waitForLog("\"run-7f3a\""); // 5 s interval, 30 s timeout
    tail.stop();
    List<String> streams = query.listStreams();
    ```
