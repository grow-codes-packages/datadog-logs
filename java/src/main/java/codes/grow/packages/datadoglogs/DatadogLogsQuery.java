package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.CancelledException;
import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.core.HttpTransport;
import codes.grow.packages.datadoglogs.core.Json;
import codes.grow.packages.datadoglogs.core.Poll;
import codes.grow.packages.datadoglogs.core.TransportRequest;
import codes.grow.packages.datadoglogs.core.TransportResponse;
import codes.grow.packages.datadoglogs.core.UnavailableException;
import codes.grow.packages.datadoglogs.core.UnsupportedException;
import codes.grow.packages.datadoglogs.core.ValidationException;
import codes.grow.packages.datadoglogs.logging.LogQueryClient;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.LogRecordJson;
import codes.grow.packages.datadoglogs.logging.LogSearchResult;
import codes.grow.packages.datadoglogs.logging.SearchOptions;
import codes.grow.packages.datadoglogs.logging.SortOrder;
import codes.grow.packages.datadoglogs.logging.TailHandle;
import codes.grow.packages.datadoglogs.logging.TailOptions;
import codes.grow.packages.datadoglogs.logging.WaitForLogOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Datadog Logs query client (needs the application key; API.md section 6; DECISIONS 3, 5, 8).
 *
 * <p>{@link #searchLogs} (one page), {@link #iterateLogs} (cursor pagination), {@link
 * #aggregateLogs}, {@link #listIndexes}, and the category {@link LogQueryClient} methods: {@link
 * #search}, {@link #waitForLog}, {@link #tail} (polling search: Datadog Live Tail has no public
 * API), {@link #listStreams} (index names, needs {@code logs_read_config}); {@link #listLabels}
 * throws {@link UnsupportedException}. Get it from {@link DatadogLogsClient#query()}.
 *
 * <pre>{@code
 * DatadogLogsQuery query = client.query();
 * DatadogLogsPage page = query.searchLogs(
 *     SearchLogsRequest.builder().query("service:checkout").from("now-1h").limit(50).build());
 * }</pre>
 */
public final class DatadogLogsQuery implements LogQueryClient {
  /** Search path ({@code POST}). */
  public static final String SEARCH_PATH = "/api/v2/logs/events/search";

  /** Aggregate path ({@code POST}). */
  public static final String AGGREGATE_PATH = "/api/v2/logs/analytics/aggregate";

  /** Indexes path ({@code GET}). */
  public static final String INDEXES_PATH = "/api/v1/logs/config/indexes";

  /** Largest page size accepted by the search API. */
  public static final int MAX_PAGE_LIMIT = 5000;

  /** Page size of {@code searchLogs} when none is given. */
  public static final int DEFAULT_SEARCH_LIMIT = 100;

  /** Page size of {@link #iterateLogs} when none is given. */
  public static final int DEFAULT_ITERATOR_PAGE_SIZE = 1000;

  /** Poll interval of {@link #waitForLog(String)} (the Datadog search rate limit is low). */
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

  /** How far back each tail poll searches again before the newest seen log. */
  public static final Duration TAIL_LAG = Duration.ofMinutes(1);

  private final DatadogLogsClient client;
  private final DatadogHttp http;

  DatadogLogsQuery(DatadogLogsClient client, DatadogHttp http) {
    this.client = client;
    this.http = http;
  }

  private TransportRequest.Builder request(String method, String path, String operation) {
    DatadogLogsConfig config = http.config();
    return TransportRequest.builder(method, DatadogHttp.join(config.apiUrl(), path))
        .header(DatadogHttp.API_KEY_HEADER, config.apiKey().map(s -> s.reveal()).orElse(""))
        .header(DatadogHttp.APP_KEY_HEADER, config.appKey().map(s -> s.reveal()).orElse(""))
        .operation(operation);
  }

  /** Sends a read-only POST (idempotent: 5xx are retried) and returns the JSON object body. */
  private CompletableFuture<Parsed> post(String path, String operation, Object body) {
    return http.sendAsync(request("POST", path, operation).json(body).idempotent(true))
        .thenApply(r -> parse(r, operation));
  }

  /** A JSON object response body and the response request id. */
  private record Parsed(JsonNode body, String requestId) {}

  private static Parsed parse(TransportResponse r, String operation) {
    JsonNode node;
    try {
      node = Json.tree(r.body());
    } catch (ValidationException e) {
      node = null;
    }
    if (node == null || !node.isObject()) {
      throw unexpected(operation, "no JSON object", r.status());
    }
    return new Parsed(node, r.requestId().orElse(null));
  }

  private static UnavailableException unexpected(String operation, String what, int status) {
    return new UnavailableException(
        operation + ": unexpected response (" + what + ")", status, null, null, null);
  }

  private static void checkLimit(Integer value, String op, String name) {
    if (value != null && (value < 1 || value > MAX_PAGE_LIMIT)) {
      throw new ValidationException(op + ": " + name + " must be between 1 and " + MAX_PAGE_LIMIT);
    }
  }

  /**
   * Returns one page of logs matching the request ({@code POST /api/v2/logs/events/search}). Pass
   * {@link DatadogLogsPage#nextCursor()} back as {@code cursor} (with the same filter and sort) for
   * the next page. A {@code timeout} status and warnings mean partial results and are returned, not
   * raised.
   *
   * @param request filter, limit (1..5000, default 100), sort and cursor
   * @return the page
   * @throws ValidationException for a limit outside 1..5000
   * @throws codes.grow.packages.datadoglogs.core.AuthenticationException for a missing, invalid or
   *     under-privileged key (401/403)
   * @throws UnavailableException for an answer without a {@code data} array
   */
  public DatadogLogsPage searchLogs(SearchLogsRequest request) {
    return HttpTransport.await(searchLogsAsync(request), "logs.searchLogs");
  }

  /**
   * Asynchronous {@link #searchLogs}.
   *
   * @param request the request
   * @return a future with the page
   */
  public CompletableFuture<DatadogLogsPage> searchLogsAsync(SearchLogsRequest request) {
    String op = "logs.searchLogs";
    Map<String, Object> body = new LinkedHashMap<>();
    try {
      Integer limit = request.limit().isPresent() ? request.limit().getAsInt() : null;
      checkLimit(limit, op, "limit");
      Map<String, Object> page = new LinkedHashMap<>();
      page.put("limit", limit == null ? DEFAULT_SEARCH_LIMIT : limit);
      request.cursor().ifPresent(c -> page.put("cursor", c));
      body.put("filter", request.filterBody());
      body.put("sort", request.sort().wireValue());
      body.put("page", page);
    } catch (ValidationException e) {
      return CompletableFuture.failedFuture(e);
    }
    return post(SEARCH_PATH, op, body)
        .thenApply(
            p -> {
              JsonNode data = p.body().get("data");
              if (data == null || !data.isArray()) {
                throw unexpected(op, "no data array", 200);
              }
              List<DatadogLogEvent> events = new ArrayList<>();
              for (JsonNode item : data) {
                DatadogLogMapping.parseLogEvent(item).ifPresent(events::add);
              }
              return new DatadogLogsPage(events, parseMeta(p));
            });
  }

  private static DatadogQueryMeta parseMeta(Parsed p) {
    JsonNode meta = p.body().path("meta");
    JsonNode after = meta.path("page").path("after");
    Optional<String> next =
        after.isTextual() && !after.asText().isEmpty()
            ? Optional.of(after.asText())
            : Optional.empty();
    List<DatadogWarning> warnings = new ArrayList<>();
    for (JsonNode w : meta.path("warnings")) {
      if (w.isObject()) {
        warnings.add(
            new DatadogWarning(text(w.get("code")), text(w.get("title")), text(w.get("detail"))));
      }
    }
    String requestId = text(meta.get("request_id"));
    JsonNode elapsed = meta.get("elapsed");
    return new DatadogQueryMeta(
        next,
        Optional.ofNullable(text(meta.get("status"))),
        warnings,
        Optional.ofNullable(requestId != null ? requestId : p.requestId()),
        elapsed != null && elapsed.isNumber()
            ? Optional.of(Duration.ofMillis(elapsed.asLong()))
            : Optional.empty());
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  /**
   * Iterates over every matching event across pages (cursor pagination), lazily: each page is
   * fetched when the previous one is consumed. The request limit caps the total number of events
   * (default all); a repeated cursor raises {@link UnavailableException} (protocol loop guard).
   * Errors are thrown from {@code hasNext()} / {@code next()}.
   *
   * <pre>{@code
   * for (DatadogLogEvent e : query.iterateLogs(SearchLogsRequest.of("status:error"))) {
   *   System.out.println(e.message());
   * }
   * }</pre>
   *
   * @param request filter, total limit, page size (1..5000, default min(limit, 1000)), sort
   * @return an iterable that can be iterated once per call of {@code iterator()}
   * @throws ValidationException for a non-positive limit or a page size outside 1..5000
   */
  public Iterable<DatadogLogEvent> iterateLogs(SearchLogsRequest request) {
    String op = "logs.iterateLogs";
    if (request.limit().isPresent() && request.limit().getAsInt() < 1) {
      throw new ValidationException(op + ": limit must be a positive integer");
    }
    Integer ps = request.pageSize().isPresent() ? request.pageSize().getAsInt() : null;
    checkLimit(ps, op, "pageSize");
    long total = request.limit().isPresent() ? request.limit().getAsInt() : Long.MAX_VALUE;
    int pageSize = ps != null ? ps : (int) Math.min(total, (long) DEFAULT_ITERATOR_PAGE_SIZE);
    return () -> new PageIterator(request, total, pageSize);
  }

  private final class PageIterator implements Iterator<DatadogLogEvent> {
    private final SearchLogsRequest base;
    private final long total;
    private final int pageSize;
    private final Set<String> seen = new HashSet<>();
    private Iterator<DatadogLogEvent> current = List.<DatadogLogEvent>of().iterator();
    private String cursor;
    private long count;
    private boolean last;
    private boolean repeated;

    PageIterator(SearchLogsRequest base, long total, int pageSize) {
      this.base = base;
      this.total = total;
      this.pageSize = pageSize;
      this.cursor = base.cursor().orElse(null);
    }

    @Override
    public boolean hasNext() {
      while (count < total && !current.hasNext() && !last) {
        if (repeated) {
          throw new UnavailableException("logs.iterateLogs: the service repeated a page cursor");
        }
        fetch();
      }
      return count < total && current.hasNext();
    }

    private void fetch() {
      int limit = (int) Math.min(pageSize, total - count);
      DatadogLogsPage page = searchLogs(base.toBuilder().limit(limit).cursor(cursor).build());
      current = page.events().iterator();
      Optional<String> next = page.nextCursor();
      if (next.isEmpty() || page.events().isEmpty()) {
        last = true;
        return;
      }
      if (!seen.add(next.get()) || next.get().equals(cursor)) {
        repeated = true;
      }
      cursor = next.get();
    }

    @Override
    public DatadogLogEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      count++;
      return current.next();
    }
  }

  /**
   * Computes {@code compute} (results keyed {@code c0}, {@code c1}, …) over the logs matching the
   * filter, optionally grouped by facets ({@code POST /api/v2/logs/analytics/aggregate}).
   *
   * <pre>{@code
   * DatadogAggregateResult r = query.aggregateLogs(
   *     List.of(DatadogCompute.of("count")), List.of(DatadogGroupBy.of("service")),
   *     AggregateLogsOptions.builder().query("env:prod").build());
   * }</pre>
   *
   * @param compute one or more computations (not empty)
   * @param groupBy facets to group by (may be empty)
   * @param options filter and cursor
   * @return the buckets
   * @throws ValidationException when {@code compute} is empty
   */
  public DatadogAggregateResult aggregateLogs(
      List<DatadogCompute> compute, List<DatadogGroupBy> groupBy, AggregateLogsOptions options) {
    return HttpTransport.await(aggregateLogsAsync(compute, groupBy, options), "logs.aggregateLogs");
  }

  /**
   * {@link #aggregateLogs} of one compute over the default filter.
   *
   * @param compute the computation
   * @param groupBy facets to group by
   * @return the buckets
   */
  public DatadogAggregateResult aggregateLogs(
      DatadogCompute compute, List<DatadogGroupBy> groupBy) {
    return aggregateLogs(List.of(compute), groupBy, AggregateLogsOptions.defaults());
  }

  /**
   * Asynchronous {@link #aggregateLogs}.
   *
   * @param compute computations
   * @param groupBy facets
   * @param options filter and cursor
   * @return a future with the buckets
   */
  public CompletableFuture<DatadogAggregateResult> aggregateLogsAsync(
      List<DatadogCompute> compute, List<DatadogGroupBy> groupBy, AggregateLogsOptions options) {
    String op = "logs.aggregateLogs";
    if (compute.isEmpty()) {
      return CompletableFuture.failedFuture(
          new ValidationException(op + ": compute must not be empty"));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("compute", compute.stream().map(DatadogCompute::toWire).toList());
    body.put("filter", options.filterBody());
    if (!groupBy.isEmpty()) {
      body.put("group_by", groupBy.stream().map(DatadogGroupBy::toWire).toList());
    }
    options.cursor().ifPresent(c -> body.put("page", Map.of("cursor", c)));
    return post(AGGREGATE_PATH, op, body)
        .thenApply(
            p -> {
              JsonNode buckets = p.body().path("data").path("buckets");
              if (!buckets.isArray()) {
                throw unexpected(op, "no data.buckets", 200);
              }
              List<DatadogBucket> out = new ArrayList<>();
              for (JsonNode b : buckets) {
                if (b.isObject()) {
                  out.add(parseBucket(b));
                }
              }
              return new DatadogAggregateResult(out, parseMeta(p));
            });
  }

  private static DatadogBucket parseBucket(JsonNode b) {
    Map<String, Object> by = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : b.path("by").properties()) {
      if (e.getValue().isTextual() || e.getValue().isNumber()) {
        by.put(e.getKey(), LogRecordJson.toAttrValue(e.getValue()));
      }
    }
    Map<String, Object> computes = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : b.path("computes").properties()) {
      JsonNode v = e.getValue();
      if (v.isNumber()) {
        computes.put(e.getKey(), v.asDouble());
      } else if (v.isTextual()) {
        computes.put(e.getKey(), v.asText());
      } else if (v.isArray()) {
        List<DatadogTimeseriesPoint> points = new ArrayList<>();
        for (JsonNode pt : v) {
          Instant t = parseInstant(text(pt.get("time")));
          if (t != null && pt.path("value").isNumber()) {
            points.add(new DatadogTimeseriesPoint(t, pt.get("value").asDouble()));
          }
        }
        computes.put(e.getKey(), points);
      }
    }
    return new DatadogBucket(by, computes);
  }

  private static Instant parseInstant(String text) {
    if (text == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Category search: one page of {@link #searchLogs} ({@code start}/{@code end} to {@code
   * from}/{@code to}, default the last 15 minutes; {@code order} to the sort; {@code cursor} to
   * {@code page.cursor}). {@code total} is never set (Datadog returns none).
   *
   * @param query Datadog search query
   * @param options window, limit (at most 5000), order and cursor
   * @return the records and the next cursor
   */
  @Override
  public LogSearchResult search(String query, SearchOptions options) {
    return HttpTransport.await(searchAsync(query, options), "logs.search");
  }

  @Override
  public CompletableFuture<LogSearchResult> searchAsync(String query, SearchOptions options) {
    SearchLogsRequest.Builder b =
        SearchLogsRequest.builder()
            .query(query)
            .limit(options.limit())
            .sort(
                options.order() == SortOrder.ASC ? LogsSort.TIMESTAMP_ASC : LogsSort.TIMESTAMP_DESC)
            .cursor(options.cursor().orElse(null));
    options.start().ifPresent(b::from);
    options.end().ifPresent(b::to);
    return searchLogsAsync(b.build())
        .thenApply(
            page -> new LogSearchResult(page.records(), page.nextCursor(), OptionalLong.empty()));
  }

  /**
   * Returns the log indexes ({@code GET /api/v1/logs/config/indexes}; needs the {@code
   * logs_read_config} permission).
   *
   * @return the indexes
   * @throws UnavailableException for an answer without an {@code indexes} array
   */
  public List<DatadogLogIndex> listIndexes() {
    return HttpTransport.await(listIndexesAsync(), "logs.listIndexes");
  }

  /**
   * Asynchronous {@link #listIndexes()}.
   *
   * @return a future with the indexes
   */
  public CompletableFuture<List<DatadogLogIndex>> listIndexesAsync() {
    String op = "logs.listIndexes";
    return http.sendAsync(request("GET", INDEXES_PATH, op))
        .thenApply(
            r -> {
              JsonNode indexes = parse(r, op).body().get("indexes");
              if (indexes == null || !indexes.isArray()) {
                throw unexpected(op, "no indexes array", r.status());
              }
              List<DatadogLogIndex> out = new ArrayList<>();
              for (JsonNode i : indexes) {
                String name = text(i.get("name"));
                if (name == null) {
                  continue;
                }
                JsonNode days = i.path("num_retention_days");
                JsonNode limit = i.path("daily_limit");
                JsonNode rate = i.path("is_rate_limited");
                out.add(
                    new DatadogLogIndex(
                        name,
                        Optional.ofNullable(text(i.path("filter").get("query"))),
                        days.isNumber() ? OptionalLong.of(days.asLong()) : OptionalLong.empty(),
                        limit.isNumber() ? OptionalLong.of(limit.asLong()) : OptionalLong.empty(),
                        rate.isBoolean() ? Optional.of(rate.asBoolean()) : Optional.empty()));
              }
              return out;
            });
  }

  /**
   * Index names from {@link #listIndexes()} (needs the {@code logs_read_config} permission).
   *
   * @return the names
   */
  @Override
  public List<String> listStreams() {
    return HttpTransport.await(listStreamsAsync(), "logs.listStreams");
  }

  @Override
  public CompletableFuture<List<String>> listStreamsAsync() {
    return listIndexesAsync().thenApply(list -> list.stream().map(DatadogLogIndex::name).toList());
  }

  /**
   * Datadog has no public API that lists facets or attributes.
   *
   * @return never
   * @throws UnsupportedException always
   */
  @Override
  public List<String> listLabels() {
    throw labelsUnsupported();
  }

  @Override
  public CompletableFuture<List<String>> listLabelsAsync() {
    return CompletableFuture.failedFuture(labelsUnsupported());
  }

  private static UnsupportedException labelsUnsupported() {
    return new UnsupportedException(
        "logs.listLabels: Datadog has no API that lists log attributes");
  }

  /**
   * Polls {@link #search} until a record matches (interval {@link #DEFAULT_POLL_INTERVAL}, timeout
   * 30 s). Authentication and validation errors propagate at once; {@link
   * codes.grow.packages.datadoglogs.core.TimeoutException} at the deadline.
   *
   * @param query Datadog search query, ideally with a run-unique marker
   * @return the first matching record
   */
  @Override
  public LogRecord waitForLog(String query) {
    return waitForLog(query, new WaitForLogOptions(null, DEFAULT_POLL_INTERVAL, null, null));
  }

  @Override
  public CompletableFuture<LogRecord> waitForLogAsync(String query, WaitForLogOptions options) {
    return Poll.untilAsync(
        "logs.waitForLog",
        options.timeout(),
        options.interval(),
        () ->
            searchAsync(query, options.search())
                .thenApply(r -> r.records().stream().filter(options.predicate()).findFirst()));
  }

  /**
   * Polling tail: searches every {@code options.interval()} for logs matching {@code query} from
   * {@code options.start()} (default now), oldest first, and calls {@code onRecord} once per log
   * id. Retryable errors keep the tail polling; other errors go to {@code options.onError()} and
   * end it. {@link DatadogLogsClient#close()} stops it. Mind the search rate limit (an interval of
   * 5 s or more is recommended).
   *
   * @param query Datadog search query
   * @param onRecord callback, called from the tail thread
   * @param options start, interval and error callback
   * @return the running tail
   */
  @Override
  public DatadogLogTail tail(String query, Consumer<LogRecord> onRecord, TailOptions options) {
    if (onRecord == null) {
      throw new ValidationException("logs.tail: onRecord is required");
    }
    DatadogLogTail tail = new DatadogLogTail(this, query, onRecord, options);
    client.register(tail);
    tail.start();
    return tail;
  }

  /** A running {@link #tail}. {@link #stop()} is idempotent and waits for the poll thread. */
  public static final class DatadogLogTail implements TailHandle {
    private final DatadogLogsQuery query;
    private final String text;
    private final Consumer<LogRecord> onRecord;
    private final TailOptions options;
    private final Thread thread;
    private volatile boolean stopped;
    private volatile DatadogLogsException error;

    DatadogLogTail(
        DatadogLogsQuery query, String text, Consumer<LogRecord> onRecord, TailOptions options) {
      this.query = query;
      this.text = text;
      this.onRecord = onRecord;
      this.options = options;
      this.thread = new Thread(this::run, "datadog-logs-tail");
      this.thread.setDaemon(true);
    }

    void start() {
      thread.start();
    }

    /**
     * Returns whether the poll thread is still running.
     *
     * @return true while polling
     */
    public boolean isRunning() {
      return thread.isAlive();
    }

    /**
     * Returns the non-retryable error that ended the tail.
     *
     * @return the error, or empty (stopped or still running)
     */
    public Optional<DatadogLogsException> error() {
      return Optional.ofNullable(error);
    }

    @Override
    public void stop() {
      stopped = true;
      thread.interrupt();
      if (Thread.currentThread() != thread) {
        try {
          thread.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void run() {
      Instant start = options.start().orElse(Instant.now());
      Instant newest = start;
      Map<String, Instant> seen = new HashMap<>();
      while (!stopped) {
        Instant from = newest.minus(TAIL_LAG);
        if (from.isBefore(start)) {
          from = start;
        }
        try {
          SearchLogsRequest req =
              SearchLogsRequest.builder()
                  .query(text)
                  .from(from)
                  .to(DatadogLogsFilter.DEFAULT_TO)
                  .sort(LogsSort.TIMESTAMP_ASC)
                  .build();
          for (DatadogLogEvent e : query.iterateLogs(req)) {
            Instant ts = e.timestamp().orElse(start);
            if (seen.putIfAbsent(e.id(), ts) != null) {
              continue;
            }
            if (ts.isAfter(newest)) {
              newest = ts;
            }
            deliver(e.toLogRecord());
          }
        } catch (CancelledException e) {
          return;
        } catch (DatadogLogsException e) {
          if (stopped) {
            return;
          }
          if (!e.retryable()) {
            error = e;
            safeError(e);
            return;
          }
        }
        Instant cutoff = newest.minus(TAIL_LAG.multipliedBy(2));
        seen.values().removeIf(t -> t.isBefore(cutoff));
        try {
          Thread.sleep(options.interval().toMillis());
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    private void deliver(LogRecord record) {
      try {
        onRecord.accept(record);
      } catch (RuntimeException ignored) {
        // a failing callback must not end the tail
      }
    }

    private void safeError(DatadogLogsException e) {
      try {
        options.onError().accept(e);
      } catch (RuntimeException ignored) {
        // a failing callback must not break the tail thread
      }
    }
  }
}
