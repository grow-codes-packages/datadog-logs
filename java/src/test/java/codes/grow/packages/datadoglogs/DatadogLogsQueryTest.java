package codes.grow.packages.datadoglogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadoglogs.core.AuthenticationException;
import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.core.RateLimitException;
import codes.grow.packages.datadoglogs.core.TimeoutException;
import codes.grow.packages.datadoglogs.core.UnavailableException;
import codes.grow.packages.datadoglogs.core.UnsupportedException;
import codes.grow.packages.datadoglogs.core.ValidationException;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.LogSearchResult;
import codes.grow.packages.datadoglogs.logging.SearchOptions;
import codes.grow.packages.datadoglogs.logging.SortOrder;
import codes.grow.packages.datadoglogs.logging.TailOptions;
import codes.grow.packages.datadoglogs.logging.WaitForLogOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogLogsQueryTest {
  private static final String SEARCH = DatadogLogsQuery.SEARCH_PATH;
  private static final String AGG = DatadogLogsQuery.AGGREGATE_PATH;
  private static final String IDX = DatadogLogsQuery.INDEXES_PATH;

  private DdServer server;
  private DatadogLogsClient client;
  private DatadogLogsQuery query;

  @BeforeEach
  void start() {
    server = new DdServer();
    client = DatadogLogsClientTest.client(server);
    query = client.query();
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  @Test
  void searchLogsFirstPage() {
    server.fixture(SEARCH, "searchLogs.page1");
    DatadogLogsPage page =
        query.searchLogs(
            SearchLogsRequest.builder()
                .query("service:checkout \"run-7f3a\"")
                .from("now-1h")
                .limit(2)
                .indexes("main")
                .storageTier(StorageTier.FLEX)
                .build());
    assertThat(page.events()).hasSize(2);
    assertThat(page.nextCursor()).hasValueSatisfying(c -> assertThat(c).startsWith("eyJ"));
    assertThat(page.meta().requestId()).isPresent();
    assertThat(page.meta().status()).contains("done");
    assertThat(page.meta().elapsed()).contains(Duration.ofMillis(38));
    assertThat(page.meta().warnings()).isEmpty();
    assertThat(page.records().get(0).body()).isEqualTo("user signed in run-7f3a #2");
    DdServer.Req req = server.requests(SEARCH).get(0);
    assertThat(req.header("DD-API-KEY")).isEqualTo(DatadogLogsClientTest.API_KEY);
    assertThat(req.header("DD-APPLICATION-KEY")).isEqualTo(DatadogLogsClientTest.APP_KEY);
    JsonNode body = req.json();
    assertThat(body.at("/filter/query").asText()).isEqualTo("service:checkout \"run-7f3a\"");
    assertThat(body.at("/filter/from").asText()).isEqualTo("now-1h");
    assertThat(body.at("/filter/to").asText()).isEqualTo("now");
    assertThat(body.at("/filter/indexes/0").asText()).isEqualTo("main");
    assertThat(body.at("/filter/storage_tier").asText()).isEqualTo("flex");
    assertThat(body.get("sort").asText()).isEqualTo("-timestamp");
    assertThat(body.at("/page/limit").asInt()).isEqualTo(2);
    assertThat(body.at("/page/cursor").isMissingNode()).isTrue();
    assertThat(body.has("options")).isFalse();
  }

  @Test
  void searchLogsDefaultsAndCursor() {
    server.fixture(SEARCH, "searchLogs.empty");
    Instant from = Instant.parse("2026-11-18T12:00:00Z");
    SearchLogsRequest req =
        SearchLogsRequest.builder()
            .from(from)
            .to(from.plusSeconds(60))
            .sort(LogsSort.TIMESTAMP_ASC)
            .cursor("abc")
            .indexes(List.of())
            .query(" ")
            .build();
    assertThat(req.toBuilder().build().cursor()).contains("abc");
    DatadogLogsPage page = query.searchLogsAsync(req).join();
    assertThat(page.events()).isEmpty();
    assertThat(page.nextCursor()).isEmpty();
    JsonNode body = server.requests(SEARCH).get(0).json();
    assertThat(body.at("/filter/query").asText()).isEqualTo("*");
    assertThat(body.at("/filter/from").asText()).isEqualTo("2026-11-18T12:00:00.000Z");
    assertThat(body.at("/filter/to").asText()).isEqualTo("2026-11-18T12:01:00.000Z");
    assertThat(body.at("/filter/indexes/0").asText()).isEqualTo("*");
    assertThat(body.at("/filter/storage_tier").asText()).isEqualTo("indexes");
    assertThat(body.get("sort").asText()).isEqualTo("timestamp");
    assertThat(body.at("/page/limit").asInt()).isEqualTo(100);
    assertThat(body.at("/page/cursor").asText()).isEqualTo("abc");
    SearchLogsRequest of = SearchLogsRequest.of("x");
    assertThat(of.query()).isEqualTo("x");
    assertThat(of.from()).isEqualTo("now-15m");
    assertThat(of.to()).isEqualTo("now");
    assertThat(of.indexes()).containsExactly("*");
    assertThat(of.storageTier()).isEqualTo(StorageTier.INDEXES);
    assertThat(of.limit()).isEmpty();
    assertThat(of.pageSize()).isEmpty();
    assertThat(of.cursor()).isEmpty();
    assertThat(SearchLogsRequest.builder().cursor("").build().cursor()).isEmpty();
  }

  @Test
  void searchLogsValidation() {
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.builder().limit(0).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.builder().limit(5001).build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("5000");
    assertThatThrownBy(() -> SearchLogsRequest.builder().from(" "))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> SearchLogsRequest.builder().to((String) null))
        .isInstanceOf(ValidationException.class);
    assertThat(server.requests()).isEmpty();
  }

  @Test
  void partialResultsAndProtocolErrors() {
    server
        .fixture(SEARCH, "searchLogs.warnings_partial")
        .fixture(SEARCH, "searchLogs.timeout_status")
        .fixture(SEARCH, "searchLogs.fakeintake_default")
        .on(SEARCH, 200, "")
        .on(SEARCH, 200, "[1]")
        .on(
            SEARCH,
            200,
            "{\"data\":[1,{\"id\":\"a\"}],\"meta\":{\"warnings\":[1]}}",
            "x-request-id",
            "hdr-1");
    DatadogLogsPage warn = query.searchLogs(SearchLogsRequest.of("x"));
    assertThat(warn.meta().warnings()).hasSize(1);
    assertThat(warn.meta().warnings().get(0).code()).isEqualTo("unknown_index");
    assertThat(warn.meta().warnings().get(0).detail()).isEqualTo("indexes: nope");
    assertThat(query.searchLogs(SearchLogsRequest.of("x")).meta().status()).contains("timeout");
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.of("x")))
        .isInstanceOf(UnavailableException.class)
        .hasMessageContaining("no data array");
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.of("x")))
        .isInstanceOf(UnavailableException.class)
        .hasMessageContaining("no JSON object");
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.of("x")))
        .isInstanceOf(UnavailableException.class);
    DatadogLogsPage odd = query.searchLogs(SearchLogsRequest.of("x"));
    assertThat(odd.events()).hasSize(1);
    assertThat(odd.meta().requestId()).contains("hdr-1");
    assertThat(odd.meta().warnings()).isEmpty();
    assertThat(odd.meta().elapsed()).isEmpty();
    assertThat(new DatadogWarning(null, null, null).title()).isEmpty();
  }

  @Test
  void searchErrors() {
    server
        .fixture(SEARCH, "searchLogs.401_missing_keys")
        .fixture(SEARCH, "searchLogs.403_invalid_keys")
        .fixture(SEARCH, "searchLogs.400_invalid_query")
        .fixture(SEARCH, "searchLogs.429_rate_limited");
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.of("x")))
        .isInstanceOf(AuthenticationException.class);
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.of("x")))
        .isInstanceOfSatisfying(
            AuthenticationException.class, e -> assertThat(e.httpStatus()).hasValue(403));
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.of("x")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query.searchLogs(SearchLogsRequest.of("x")))
        .isInstanceOfSatisfying(
            RateLimitException.class,
            e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(1820)));
  }

  @Test
  void iterateLogsFollowsCursors() {
    server.fixture(SEARCH, "searchLogs.page1").fixture(SEARCH, "searchLogs.page2_last");
    List<String> messages = new ArrayList<>();
    for (DatadogLogEvent e : query.iterateLogs(SearchLogsRequest.builder().query("x").build())) {
      messages.add(e.message());
    }
    assertThat(messages).hasSize(3).last().asString().endsWith("#0");
    List<DdServer.Req> reqs = server.requests(SEARCH);
    assertThat(reqs.get(0).json().at("/page/limit").asInt()).isEqualTo(1000);
    assertThat(reqs.get(1).json().at("/page/cursor").asText()).startsWith("eyJ");

    server.fixture(SEARCH, "searchLogs.page1");
    Iterator<DatadogLogEvent> it =
        query.iterateLogs(SearchLogsRequest.builder().limit(2).pageSize(10).build()).iterator();
    assertThat(it.next()).isNotNull();
    assertThat(it.next()).isNotNull();
    assertThat(it.hasNext()).isFalse();
    assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    assertThat(server.requests(SEARCH).get(2).json().at("/page/limit").asInt()).isEqualTo(2);

    server.fixture(SEARCH, "searchLogs.page1").fixture(SEARCH, "searchLogs.page1");
    Iterator<DatadogLogEvent> loop = query.iterateLogs(SearchLogsRequest.of("x")).iterator();
    for (int i = 0; i < 4; i++) {
      loop.next();
    }
    assertThatThrownBy(loop::hasNext)
        .isInstanceOf(UnavailableException.class)
        .hasMessageContaining("repeated a page cursor");

    server.on(SEARCH, 200, "{\"data\":[],\"meta\":{\"page\":{\"after\":\"c\"}}}");
    assertThat(query.iterateLogs(SearchLogsRequest.of("x")).iterator().hasNext()).isFalse();
    server.on(SEARCH, 200, "{\"data\":[{\"id\":\"1\"}],\"meta\":{\"page\":{\"after\":\"same\"}}}");
    Iterator<DatadogLogEvent> same =
        query.iterateLogs(SearchLogsRequest.builder().cursor("same").build()).iterator();
    same.next();
    assertThatThrownBy(same::hasNext).isInstanceOf(UnavailableException.class);

    assertThatThrownBy(() -> query.iterateLogs(SearchLogsRequest.builder().limit(0).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> query.iterateLogs(SearchLogsRequest.builder().pageSize(6000).build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aggregateLogs() {
    server
        .fixture(AGG, "aggregateLogs.count_by_service")
        .fixture(AGG, "aggregateLogs.timeseries")
        .fixture(AGG, "aggregateLogs.paged");
    DatadogAggregateResult r =
        query.aggregateLogs(
            List.of(DatadogCompute.of("count"), DatadogCompute.of("avg").withMetric("@duration")),
            List.of(
                DatadogGroupBy.builder("service")
                    .limit(5)
                    .sortByMeasure("count", "desc", null)
                    .missing("none")
                    .total(true)
                    .build()),
            AggregateLogsOptions.builder().query("env:prod").from("now-1h").cursor("cur").build());
    assertThat(r.buckets()).hasSize(1);
    DatadogBucket b = r.buckets().get(0);
    assertThat(b.by()).containsEntry("service", "checkout");
    assertThat(b.number("c0")).hasValue(3.0);
    assertThat(b.number("c1")).isEmpty();
    assertThat(b.text("c0")).isEmpty();
    assertThat(b.timeseries("c0")).isEmpty();
    assertThat(r.nextCursor()).isEmpty();
    assertThat(r.meta().requestId()).isPresent();
    JsonNode body = server.requests(AGG).get(0).json();
    assertThat(body.at("/compute/0/aggregation").asText()).isEqualTo("count");
    assertThat(body.at("/compute/0/type").asText()).isEqualTo("total");
    assertThat(body.at("/compute/1/metric").asText()).isEqualTo("@duration");
    assertThat(body.at("/group_by/0/facet").asText()).isEqualTo("service");
    assertThat(body.at("/group_by/0/limit").asInt()).isEqualTo(5);
    assertThat(body.at("/group_by/0/sort/type").asText()).isEqualTo("measure");
    assertThat(body.at("/group_by/0/missing").asText()).isEqualTo("none");
    assertThat(body.at("/group_by/0/total").asBoolean()).isTrue();
    assertThat(body.at("/filter/query").asText()).isEqualTo("env:prod");
    assertThat(body.at("/page/cursor").asText()).isEqualTo("cur");

    DatadogAggregateResult ts =
        query.aggregateLogs(DatadogCompute.of("count").timeseries("5m"), List.of());
    assertThat(ts.buckets().get(0).timeseries("c0")).hasSize(3);
    assertThat(ts.buckets().get(0).timeseries("c0").get(0).value()).isEqualTo(3.0);
    JsonNode tsBody = server.requests(AGG).get(1).json();
    assertThat(tsBody.at("/compute/0/type").asText()).isEqualTo("timeseries");
    assertThat(tsBody.at("/compute/0/interval").asText()).isEqualTo("5m");
    assertThat(tsBody.has("group_by")).isFalse();
    assertThat(tsBody.has("page")).isFalse();

    DatadogAggregateResult paged =
        query
            .aggregateLogsAsync(
                List.of(DatadogCompute.of("count")),
                List.of(DatadogGroupBy.of("@user.id")),
                AggregateLogsOptions.defaults())
            .join();
    assertThat(paged.nextCursor()).isPresent();
    assertThat(paged.buckets().get(0).by()).containsEntry("@user.id", 42L);
  }

  @Test
  void aggregateEdgeCases() {
    assertThatThrownBy(
            () -> query.aggregateLogs(List.of(), List.of(), AggregateLogsOptions.defaults()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogCompute.of(" ")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogGroupBy.builder("")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogGroupBy.builder(null)).isInstanceOf(ValidationException.class);
    DatadogGroupBy g =
        DatadogGroupBy.builder("@n")
            .sortAlphabetical("asc")
            .histogram(10, 0, 100)
            .sortByMeasure("avg", "asc", "@duration")
            .build();
    assertThat(g.facet()).isEqualTo("@n");
    assertThat(g.toWire()).containsKeys("facet", "sort", "histogram");
    assertThat(((Map<?, ?>) g.toWire().get("sort")).get("metric")).isEqualTo("@duration");
    assertThat(DatadogGroupBy.builder("x").sortAlphabetical("desc").build().toWire().get("sort"))
        .isEqualTo(Map.of("type", "alphabetical", "order", "desc"));
    assertThat(new DatadogCompute("count", null, null, null).type()).isEqualTo("total");

    server
        .on(AGG, 200, "{\"data\":{}}")
        .on(
            AGG,
            200,
            "{\"data\":{\"buckets\":[1,{\"by\":{\"a\":true,\"b\":1.5},\"computes\":{\"c0\":\"x\",\"c1\":{},\"c2\":[{\"time\":\"bad\",\"value\":1},{\"time\":\"2026-11-18T12:00:00Z\",\"value\":\"n\"},5]}}]}}");
    assertThatThrownBy(() -> query.aggregateLogs(DatadogCompute.of("count"), List.of()))
        .isInstanceOf(UnavailableException.class)
        .hasMessageContaining("data.buckets");
    DatadogBucket b = query.aggregateLogs(DatadogCompute.of("count"), List.of()).buckets().get(0);
    assertThat(b.by()).containsOnlyKeys("b");
    assertThat(b.text("c0")).contains("x");
    assertThat(b.computes()).doesNotContainKey("c1");
    assertThat(b.timeseries("c2")).isEmpty();
  }

  @Test
  void indexesAndLabels() {
    server
        .fixture(IDX, "listStreams.indexes")
        .on(IDX, 200, "{\"indexes\":[{\"name\":\"a\"},{\"filter\":{}}]}")
        .fixture(IDX, "listStreams.indexes")
        .on(IDX, 200, "")
        .on(IDX, 200, "{\"other\":1}");
    List<DatadogLogIndex> ix = query.listIndexes();
    assertThat(ix).hasSize(1);
    DatadogLogIndex main = ix.get(0);
    assertThat(main.name()).isEqualTo("main");
    assertThat(main.query()).contains("*");
    assertThat(main.retentionDays()).hasValue(15);
    assertThat(main.dailyLimit()).hasValue(300_000_000);
    assertThat(main.rateLimited()).contains(false);
    DatadogLogIndex bare = query.listIndexesAsync().join().get(0);
    assertThat(bare.query()).isEmpty();
    assertThat(bare.retentionDays()).isEmpty();
    assertThat(bare.dailyLimit()).isEmpty();
    assertThat(bare.rateLimited()).isEmpty();
    assertThat(query.listStreams()).containsExactly("main");
    assertThatThrownBy(query::listStreams)
        .isInstanceOf(UnavailableException.class)
        .hasMessageContaining("no JSON object");
    assertThatThrownBy(() -> query.listStreamsAsync().join())
        .hasCauseInstanceOf(UnavailableException.class);
    assertThat(server.requests(IDX).get(0).method()).isEqualTo("GET");
    assertThat(server.requests(IDX).get(0).header("DD-APPLICATION-KEY"))
        .isEqualTo(DatadogLogsClientTest.APP_KEY);
    assertThatThrownBy(query::listLabels).isInstanceOf(UnsupportedException.class);
    assertThatThrownBy(() -> query.listLabelsAsync().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(UnsupportedException.class);
  }

  @Test
  void categorySearch() {
    server.fixture(SEARCH, "searchLogs.page1").fixture(SEARCH, "searchLogs.page2_last");
    Instant start = Instant.parse("2026-11-18T12:00:00Z");
    LogSearchResult r =
        query.search(
            "run-7f3a",
            SearchOptions.builder()
                .start(start)
                .end(start.plusSeconds(900))
                .limit(2)
                .order(SortOrder.ASC)
                .cursor("c1")
                .build());
    assertThat(r.records()).hasSize(2);
    assertThat(r.nextCursor()).isPresent();
    assertThat(r.total()).isEmpty();
    JsonNode body = server.requests(SEARCH).get(0).json();
    assertThat(body.get("sort").asText()).isEqualTo("timestamp");
    assertThat(body.at("/filter/from").asText()).isEqualTo("2026-11-18T12:00:00.000Z");
    assertThat(body.at("/filter/to").asText()).isEqualTo("2026-11-18T12:15:00.000Z");
    assertThat(body.at("/page/cursor").asText()).isEqualTo("c1");
    LogSearchResult d = query.search("run-7f3a");
    assertThat(d.nextCursor()).isEmpty();
    JsonNode dBody = server.requests(SEARCH).get(1).json();
    assertThat(dBody.get("sort").asText()).isEqualTo("-timestamp");
    assertThat(dBody.at("/filter/from").asText()).isEqualTo("now-15m");
  }

  @Test
  void waitForLog() {
    server
        .fixture(SEARCH, "searchLogs.empty")
        .on(SEARCH, 503, "{}")
        .fixture(SEARCH, "searchLogs.page1");
    LogRecord r =
        query.waitForLog(
            "run-7f3a", WaitForLogOptions.of(Duration.ofSeconds(10), Duration.ofMillis(20)));
    assertThat(r.body()).contains("run-7f3a");

    server.fixture(SEARCH, "searchLogs.page1");
    assertThat(query.waitForLog("run-7f3a").body()).contains("#2");

    server.fixture(SEARCH, "searchLogs.empty").fixture(SEARCH, "searchLogs.page1");
    LogRecord picked =
        query
            .waitForLogAsync(
                "run-7f3a",
                WaitForLogOptions.of(Duration.ofSeconds(10), Duration.ofMillis(20))
                    .withPredicate(rec -> rec.body().endsWith("#1")))
            .join();
    assertThat(picked.body()).endsWith("#1");

    server.always(SEARCH, 200, Fixtures.body("searchLogs.empty"));
    assertThatThrownBy(
            () ->
                query.waitForLog(
                    "never", WaitForLogOptions.of(Duration.ofMillis(200), Duration.ofMillis(50))))
        .isInstanceOf(TimeoutException.class);
    server.on(SEARCH, 400, "{\"errors\":[\"bad query\"]}");
    assertThatThrownBy(
            () ->
                query
                    .waitForLogAsync(
                        "bad", WaitForLogOptions.of(Duration.ofSeconds(5), Duration.ofMillis(20)))
                    .join())
        .hasCauseInstanceOf(ValidationException.class);
  }

  private static String eventPage(String id, String ts, String message) {
    return "{\"data\":[{\"id\":\""
        + id
        + "\",\"type\":\"log\",\"attributes\":{\"timestamp\":\""
        + ts
        + "\",\"status\":\"info\",\"message\":\""
        + message
        + "\"}}],\"meta\":{\"status\":\"done\"}}";
  }

  @Test
  void tailDeliversEachLogOnce() throws Exception {
    String now = Instant.now().toString();
    server
        .on(SEARCH, 503, "{}")
        .on(SEARCH, 503, "{}")
        .on(SEARCH, 503, "{}")
        .on(SEARCH, 503, "{}")
        .always(SEARCH, 200, eventPage("id-1", now, "tail hello"));
    List<LogRecord> got = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    DatadogLogsQuery.DatadogLogTail tail =
        query.tail(
            "tail",
            rec -> {
              got.add(rec);
              throw new IllegalStateException("callback failures are ignored");
            },
            new TailOptions(
                Optional.of(Instant.now().minusSeconds(3600)), Duration.ofMillis(30), errors::add));
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (server.requests(SEARCH).size() < 8 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(tail.isRunning()).isTrue();
    tail.stop();
    tail.stop();
    assertThat(tail.isRunning()).isFalse();
    assertThat(got).hasSize(1);
    assertThat(got.get(0).body()).isEqualTo("tail hello");
    assertThat(errors).isEmpty();
    assertThat(tail.error()).isEmpty();
    JsonNode body = server.requests(SEARCH).get(0).json();
    assertThat(body.get("sort").asText()).isEqualTo("timestamp");
    assertThat(body.at("/filter/to").asText()).isEqualTo("now");
  }

  @Test
  void tailEndsOnNonRetryableError() throws Exception {
    server.always(SEARCH, 400, "{\"errors\":[\"bad query\"]}");
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    DatadogLogsQuery.DatadogLogTail tail =
        query.tail(
            "bad",
            rec -> {},
            TailOptions.defaults()
                .withOnError(
                    e -> {
                      errors.add(e);
                      throw new IllegalStateException("ignored");
                    }));
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (tail.isRunning() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(tail.isRunning()).isFalse();
    assertThat(errors).singleElement().isInstanceOf(ValidationException.class);
    assertThat(tail.error()).containsInstanceOf(ValidationException.class);
    tail.close();
  }

  @Test
  void tailStoppedByClientClose() throws Exception {
    server.always(SEARCH, 200, eventPage("id-x", "bad-time", "m"));
    DatadogLogsQuery.DatadogLogTail tail =
        query.tail("x", rec -> {}, new TailOptions(null, Duration.ofMillis(20), null));
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (server.requests(SEARCH).size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    client.close();
    assertThat(tail.isRunning()).isFalse();
    assertThatThrownBy(() -> query.tail("x", null, TailOptions.defaults()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void tailAfterCloseIsRejected() {
    client.close();
    assertThatThrownBy(() -> query.tail("x", r -> {}, TailOptions.defaults()))
        .isInstanceOf(DatadogLogsException.class);
  }
}
