# Search and aggregate

`client.query()` returns the `DatadogLogsQuery` client. It needs an **application key** (`appKey`,
`DD_APP_KEY`) with `logs_read_data` (and `logs_read_config` for indexes); without one, `query()`
raises `CONFIG`. Every request carries `DD-API-KEY` and `DD-APPLICATION-KEY` and goes to
`https://api.<site>`. These APIs exist only in the Datadog SaaS (see
[Compatibility](../compatibility.md)).

| Function | Request | Notes |
| - | - | - |
| `searchLogs(request)` | `POST /api/v2/logs/events/search` | one page; `query` default `*`, `from`/`to` default `now-15m`/`now`, `limit` 1..5000 (default 100), `sort` `-timestamp` (newest first) or `timestamp`, `indexes` default `["*"]`, `storageTier` `indexes` / `online-archives` / `flex`, `cursor`; returns `events`, `nextCursor`, `status` (`done` / `timeout`), `warnings`, `requestId` |
| `iterateLogs(request)` | same, repeated | follows `nextCursor`; `limit` caps the total (default all), `pageSize` the page (default `min(limit, 1000)`); a repeated cursor raises `UNAVAILABLE` (loop guard) |
| `aggregateLogs(compute, groupBy, filter)` | `POST /api/v2/logs/analytics/aggregate` | `compute`: `aggregation` (`count`, `cardinality`, `pc75`…`pc99`, `sum`, `min`, `max`, `avg`, `median`), `type` `total` (default, always sent) or `timeseries` + `interval`, `metric`; `groupBy`: `facet`, `limit`, `sort`, `missing`, `total`, `histogram`; results keyed `c0`, `c1`, … |
| `listIndexes()` | `GET /api/v1/logs/config/indexes` | `name`, `query`, `retentionDays`, `dailyLimit`, `isRateLimited` |

A 200 answer without the expected `data` raises `UNAVAILABLE` (protocol error). Search and
aggregate are read-only `POST`s and are retried like idempotent calls.

=== "TypeScript"

    ```ts
    const query = client.query();
    const page = await query.searchLogs({ query: 'service:checkout status:error', from: "now-1h", limit: 50 });
    console.log(page.events.length, page.nextCursor);

    for await (const event of query.iterateLogs({ query: "service:checkout", limit: 500 })) {
      console.log(event.timestamp?.toISOString(), event.status, event.message);
    }

    const agg = await query.aggregateLogs(
      [{ aggregation: "count" }, { aggregation: "avg", metric: "@duration" }],
      [{ facet: "status", limit: 5 }],
      { query: "service:checkout", from: "now-1h" },
    );
    for (const b of agg.buckets) console.log(b.by.status, b.computes.c0, b.computes.c1);

    console.log((await query.listIndexes()).map((i) => i.name));
    ```

=== "Rust"

    ```rust
    use futures::StreamExt;
    use grow_datadog_logs::{AggregateLogsOptions, Compute, GroupBy, SearchLogsRequest};

    let query = client.query()?;
    let page = query
        .search_logs(&SearchLogsRequest::new("service:checkout status:error").from("now-1h").limit(50))
        .await?;
    println!("{} {:?}", page.events.len(), page.next_cursor());

    let mut events = query.iterate_logs(SearchLogsRequest::new("service:checkout").limit(500));
    while let Some(event) = events.next().await {
        let event = event?;
        println!("{:?} {:?}", event.status, event.message);
    }

    let agg = query
        .aggregate_logs(
            &[Compute::new("count"), Compute::new("avg").metric("@duration")],
            &[GroupBy::new("status").limit(5)],
            &AggregateLogsOptions::new("service:checkout"),
        )
        .await?;
    for b in agg.buckets {
        println!("{} {:?} {:?}", b.by["status"], b.number("c0"), b.number("c1"));
    }
    let indexes = query.list_indexes().await?;
    ```

=== "Go"

    ```go
    query, err := client.Query()
    page, err := query.SearchLogs(ctx, datadoglogs.SearchLogsRequest{
        Query: "service:checkout status:error", From: "now-1h", Limit: 50,
    })
    fmt.Println(len(page.Events), page.NextCursor)

    for event, err := range query.IterateLogs(ctx, datadoglogs.SearchLogsRequest{Query: "service:checkout", Limit: 500}) {
        if err != nil {
            return err
        }
        fmt.Println(event.Timestamp, event.Status, event.Message)
    }

    agg, err := query.AggregateLogs(ctx,
        []datadoglogs.Compute{{Aggregation: "count"}, {Aggregation: "avg", Metric: "@duration"}},
        []datadoglogs.GroupBy{{Facet: "status", Limit: 5}},
        datadoglogs.AggregateLogsOptions{Query: "service:checkout", From: "now-1h"})
    for _, b := range agg.Buckets {
        n, _ := b.Number("c0")
        fmt.Println(b.By["status"], n)
    }
    indexes, err := query.ListIndexes(ctx)
    ```

=== "Python"

    ```python
    from grow_datadog_logs import DatadogCompute, DatadogGroupBy

    query = client.query()
    page = query.search_logs("service:checkout status:error", from_="now-1h", limit=50)
    print(len(page.events), page.next_cursor)

    for event in query.iterate_logs("service:checkout", limit=500):
        print(event.timestamp, event.status, event.message)

    agg = query.aggregate_logs(
        [DatadogCompute("count"), DatadogCompute("avg", metric="@duration")],
        [DatadogGroupBy("status", limit=5)],
        query="service:checkout",
        from_="now-1h",
    )
    for b in agg.buckets:
        print(b.by.get("status"), b.number("c0"), b.number("c1"))
    print([i.name for i in query.list_indexes()])
    ```

=== "Java"

    ```java
    DatadogLogsQuery query = client.query();
    DatadogLogsPage page = query.searchLogs(SearchLogsRequest.builder()
        .query("service:checkout status:error").from("now-1h").limit(50).build());
    System.out.println(page.events().size() + " " + page.nextCursor());

    for (DatadogLogEvent e : query.iterateLogs(
        SearchLogsRequest.builder().query("service:checkout").limit(500).build())) {
      System.out.println(e.timestamp().orElse(null) + " " + e.status() + " " + e.message());
    }

    DatadogAggregateResult agg = query.aggregateLogs(
        List.of(DatadogCompute.of("count"), DatadogCompute.of("avg").withMetric("@duration")),
        List.of(DatadogGroupBy.builder("status").limit(5).build()),
        AggregateLogsOptions.builder().query("service:checkout").from("now-1h").build());
    agg.buckets().forEach(b -> System.out.println(b.by().get("status") + " " + b.number("c0")));
    List<DatadogLogIndex> indexes = query.listIndexes();
    ```
