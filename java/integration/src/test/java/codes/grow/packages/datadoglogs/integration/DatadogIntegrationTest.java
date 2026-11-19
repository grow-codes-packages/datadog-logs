package codes.grow.packages.datadoglogs.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codes.grow.packages.datadoglogs.AggregateLogsOptions;
import codes.grow.packages.datadoglogs.DatadogAggregateResult;
import codes.grow.packages.datadoglogs.DatadogCompute;
import codes.grow.packages.datadoglogs.DatadogGroupBy;
import codes.grow.packages.datadoglogs.DatadogLogClient;
import codes.grow.packages.datadoglogs.DatadogLogEvent;
import codes.grow.packages.datadoglogs.DatadogLogExporter;
import codes.grow.packages.datadoglogs.DatadogLogIndex;
import codes.grow.packages.datadoglogs.DatadogLogMapping;
import codes.grow.packages.datadoglogs.DatadogLogsClient;
import codes.grow.packages.datadoglogs.DatadogLogsPage;
import codes.grow.packages.datadoglogs.DatadogLogsQuery;
import codes.grow.packages.datadoglogs.SearchLogsRequest;
import codes.grow.packages.datadoglogs.core.AuthenticationException;
import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.core.EnvReader;
import codes.grow.packages.datadoglogs.core.HealthStatus;
import codes.grow.packages.datadoglogs.core.Json;
import codes.grow.packages.datadoglogs.core.RateLimitException;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.core.UnavailableException;
import codes.grow.packages.datadoglogs.core.UnsupportedException;
import codes.grow.packages.datadoglogs.core.ValidationException;
import codes.grow.packages.datadoglogs.logging.BatchOptions;
import codes.grow.packages.datadoglogs.logging.BatchStats;
import codes.grow.packages.datadoglogs.logging.ExportOptions;
import codes.grow.packages.datadoglogs.logging.ExportResult;
import codes.grow.packages.datadoglogs.logging.GrowJulHandler;
import codes.grow.packages.datadoglogs.logging.LogInput;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.SearchOptions;
import codes.grow.packages.datadoglogs.logging.Severity;
import codes.grow.packages.datadoglogs.logging.TailOptions;
import codes.grow.packages.datadoglogs.logging.WaitForLogOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every public function against datadog/fakeintake (docker/compose.yml). Ingest is a real round
 * trip (send, then decode the captured payload). The query API cannot run in the container: it is
 * called against wire stubs installed through fakeintake overrides (request plumbing proven, not a
 * round trip) and unit-tested with the recorded fixtures.
 */
class DatadogIntegrationTest {
  private static final ExportOptions GZIP = new ExportOptions(true, Duration.ofSeconds(30));
  private static final ExportOptions PLAIN = new ExportOptions(false, Duration.ofSeconds(30));
  private static final SecureRandom RANDOM = new SecureRandom();

  private Map<String, String> env;
  private FakeIntake fi;
  private final List<DatadogLogsClient> clients = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    env = ConformanceTest.environment();
    assumeTrue(env.containsKey("GROW_DATADOG_LOGS_URL"), "use docker/compose.yml");
    fi = new FakeIntake(env);
    fi.restoreDefaults();
  }

  @AfterEach
  void tearDown() throws Exception {
    clients.forEach(DatadogLogsClient::close);
    if (fi != null) {
      fi.restoreDefaults();
    }
  }

  private static String run() {
    byte[] b = new byte[5];
    RANDOM.nextBytes(b);
    return "java-" + HexFormat.of().formatHex(b);
  }

  private DatadogLogsClient client(DatadogLogsClient.Builder extra) {
    DatadogLogsClient c =
        extra
            .fromEnv(EnvReader.of(env))
            .retry(RetryPolicy.builder().maxRetries(1).initialBackoff(Duration.ofMillis(5)).build())
            .build();
    clients.add(c);
    return c;
  }

  private DatadogLogsClient client() {
    return client(DatadogLogsClient.builder());
  }

  private static Map<String, JsonNode> byFirstWord(List<JsonNode> logs) {
    Map<String, JsonNode> out = new java.util.HashMap<>();
    for (JsonNode l : logs) {
      out.put(l.path("message").asText().split(" ")[0], l);
    }
    return out;
  }

  @Test
  void core() throws Exception {
    DatadogLogsClient c = client();
    assertEquals(env.get("GROW_DATADOG_LOGS_URL"), c.config().intakeUrl());
    HealthStatus h = c.health();
    assertTrue(h.healthy());
    assertEquals("ok", h.status());
    assertEquals("true", h.details().get("apiKeyValid"));
    assertTrue(c.healthAsync().join().healthy());
    assertFalse(c.ping().isNegative());
    assertFalse(c.pingAsync().join().isNegative());
    assertTrue(c.validateApiKey());
    assertTrue(c.validateApiKeyAsync().join());
    assertThrows(UnsupportedException.class, c::serverVersion);
    assertThrows(Exception.class, () -> c.serverVersionAsync().join());

    // a rejected key: the container answers 403 like Datadog does for a missing or invalid key
    fi.override("GET", "/api/v1/validate", 403, "{\"errors\":[\"Forbidden\"]}");
    assertFalse(c.validateApiKey());
    AuthenticationException e = assertThrows(AuthenticationException.class, c::ping);
    assertEquals(403, e.httpStatus().getAsInt());
    HealthStatus denied = c.health();
    assertFalse(denied.healthy());
    assertEquals("unauthorized", denied.status());

    c.close();
    c.close();
    assertTrue(c.isClosed());
  }

  @Test
  void ingestRoundTrip() throws Exception {
    String run = run();
    DatadogLogsClient c =
        client(
            DatadogLogsClient.builder()
                .service("cfg-svc")
                .env("it")
                .version("1.2.3")
                .hostname("java-host")
                .tags(List.of("team:sdk", "lang:java")));
    DatadogLogClient lc =
        c.logClient(BatchOptions.builder().flushInterval(Duration.ofHours(1)).build());
    lc.send(
        LogInput.builder("input " + run)
            .severity(Severity.WARN)
            .attribute("run", run)
            .attribute("service", "user-value")
            .attribute("password", "hunter2")
            .attribute("user", Map.of("id", 42))
            .build());
    lc.sendBatch(
        List.of(
            LogRecord.builder()
                .body("record " + run + " Grüße – 日本語   \"quoted\"")
                .severity(Severity.ERROR)
                .timestamp(Instant.now().minusSeconds(60))
                .resource(Map.of("service.name", "checkout", "deployment.environment", "test"))
                .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                .spanId("00f067aa0ba902b7")
                .traceFlags(1)
                .build()));
    lc.logger("it-logger", Map.of("component", "facade")).info("logger " + run, Map.of("k", "v"));
    java.util.logging.Logger jul = java.util.logging.Logger.getAnonymousLogger();
    jul.setUseParentHandlers(false);
    GrowJulHandler handler = new GrowJulHandler(lc);
    jul.addHandler(handler);
    jul.severe("jul " + run);
    lc.flush();
    BatchStats st = lc.stats();
    assertEquals(4, st.sent());
    assertEquals(0, st.failed());
    assertEquals(0, st.dropped());

    List<JsonNode> logs = fi.logsWith(run);
    assertEquals(4, logs.size());
    Map<String, JsonNode> by = byFirstWord(logs);
    JsonNode in = by.get("input");
    assertEquals("warn", in.get("status").asText());
    assertEquals("cfg-svc", in.get("service").asText());
    assertEquals("java-host", in.get("hostname").asText());
    assertEquals("grow-sdk", in.get("ddsource").asText());
    assertEquals("env:it,version:1.2.3,team:sdk,lang:java", in.get("ddtags").asText());
    assertEquals("user-value", in.get("attr.service").asText());
    assertEquals("[REDACTED]", in.get("password").asText());
    assertEquals(run, in.get("run").asText());
    assertEquals(42, in.at("/user/id").asInt());
    assertTrue(in.get("timestamp").isIntegralNumber());
    JsonNode rec = by.get("record");
    assertEquals("error", rec.get("status").asText());
    assertEquals("checkout", rec.get("service").asText());
    assertEquals("env:test,version:1.2.3,team:sdk,lang:java", rec.get("ddtags").asText());
    assertEquals("11803532876627986230", rec.get("dd.trace_id").asText());
    assertEquals("67667974448284343", rec.get("dd.span_id").asText());
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", rec.get("otel.trace_id").asText());
    assertEquals("00f067aa0ba902b7", rec.get("otel.span_id").asText());
    assertTrue(rec.get("message").asText().contains("Grüße – 日本語   \"quoted\""));
    JsonNode lg = by.get("logger");
    assertEquals("info", lg.get("status").asText());
    assertEquals("facade", lg.get("component").asText());
    assertEquals("v", lg.get("k").asText());
    assertEquals("error", by.get("jul").get("status").asText());
    handler.close();

    // exporter without gzip
    String plain = run();
    ExportResult r =
        c.exporter().export(List.of(LogRecord.builder().body("plain " + plain).build()), PLAIN).join();
    assertEquals(1, r.accepted());
    assertEquals("info", fi.logsWith(plain).get(0).get("status").asText());
    String raw = run();
    c.exporter().sendPayload("[{\"message\":\"raw " + raw + "\"}]", true);
    c.exporter().sendPayloadAsync("[{\"message\":\"raw " + raw + "\"}]", false).join();
    assertEquals(2, fi.logsWith(raw).size());
    assertEquals(1, c.exporter().encode(List.of(LogRecord.builder().body("x").build())).payloads().size());
    c.exporter().shutdown().join();

    // close flushes
    String closing = run();
    DatadogLogsClient c2 = client();
    DatadogLogClient lc2 =
        c2.logClient(
            DatadogLogClient.Options.builder()
                .batchOptions(BatchOptions.builder().flushInterval(Duration.ofHours(1)).build())
                .build());
    lc2.send(LogInput.of("closing " + closing));
    c2.close();
    assertEquals(1, fi.logsWith(closing).size());
  }

  @Test
  void intakeLimits() throws Exception {
    DatadogLogsClient c = client();
    String m1 = run();
    List<LogRecord> recs = new ArrayList<>();
    for (int i = 0; i < 2500; i++) {
      recs.add(LogRecord.builder().body("entry " + m1).build());
    }
    assertEquals(2500, c.exporter().export(recs, GZIP).join().accepted());
    List<FakeIntake.Payload> pls = fi.payloadsWith(m1);
    assertEquals(2500, fi.logsWith(m1).size());
    assertEquals(3, pls.size());
    pls.forEach(p -> assertTrue(p.logs().size() <= DatadogLogMapping.MAX_ENTRIES_PER_REQUEST));

    String m2 = run();
    String big = "x".repeat(900_000);
    List<LogRecord> bigs = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      bigs.add(LogRecord.builder().body("big " + m2 + " " + big).build());
    }
    assertEquals(12, c.exporter().export(bigs, GZIP).join().accepted());
    List<FakeIntake.Payload> bp = fi.payloadsWith(m2);
    assertEquals(12, fi.logsWith(m2).size());
    assertTrue(bp.size() >= 3);
    bp.forEach(p -> assertTrue(p.raw().length <= DatadogLogMapping.MAX_PAYLOAD_BYTES));

    String m3 = run();
    LogRecord huge = LogRecord.builder().body("huge " + m3 + " " + "y".repeat(1_100_000)).build();
    assertEquals(1, c.exporter().export(List.of(huge), GZIP).join().accepted());
    List<JsonNode> hl = fi.logsWith(m3);
    assertEquals(1, hl.size());
    assertTrue(hl.get(0).get(DatadogLogMapping.TRUNCATED_ATTRIBUTE).asBoolean());
    assertTrue(fi.payloadsWith(m3).get(0).raw().length - 2 <= DatadogLogMapping.MAX_LOG_BYTES);
  }

  @Test
  void intakeErrors() throws Exception {
    DatadogLogsClient c = client();
    Object[][] cases = {
      {400, "{\"errors\":[\"Bad Request\"]}", ValidationException.class, 1},
      {403, "{\"errors\":[\"Forbidden\",\"API key is missing or invalid\"]}", AuthenticationException.class, 1},
      {413, "{\"errors\":[\"Payload too large\"]}", ValidationException.class, 1},
      {429, "{\"errors\":[\"Too many requests\"]}", RateLimitException.class, 2},
      {500, "{\"errors\":[\"Internal Server Error\"]}", UnavailableException.class, 2},
      {503, "{\"errors\":[\"Service Unavailable\"]}", UnavailableException.class, 2},
    };
    for (Object[] tc : cases) {
      int status = (Integer) tc[0];
      fi.override("POST", FakeIntake.LOGS, status, (String) tc[1]);
      String marker = run();
      ExportResult r =
          c.exporter().export(List.of(LogRecord.builder().body("fault " + marker).build()), GZIP).join();
      DatadogLogsException e = r.error().orElseThrow();
      assertInstanceOf((Class<?>) tc[2], e, "status " + status);
      assertEquals(status, e.httpStatus().getAsInt());
      assertEquals((int) (Integer) tc[3], fi.logsWith(marker).size(), "attempts for " + status);
    }
  }

  private static String searchStub(String run) {
    String ts = Instant.now().toString();
    return "{\"data\":[{\"id\":\"stub-1\",\"type\":\"log\",\"attributes\":{\"timestamp\":\""
        + ts
        + "\",\"host\":\"java-host\",\"service\":\"checkout\",\"status\":\"error\",\"message\":\"stub "
        + run
        + "\",\"tags\":[\"env:it\",\"version:1.2.3\"],\"attributes\":{\"dd\":{\"trace_id\":\"11803532876627986230\",\"span_id\":\"67667974448284343\"},\"run\":\""
        + run
        + "\"}}}],\"meta\":{\"elapsed\":5,\"request_id\":\"req-"
        + run
        + "\",\"status\":\"done\"}}";
  }

  @Test
  void queryWireStubs() throws Exception {
    String run = run();
    fi.override("POST", DatadogLogsQuery.SEARCH_PATH, 200, searchStub(run));
    fi.override(
        "POST",
        DatadogLogsQuery.AGGREGATE_PATH,
        200,
        "{\"data\":{\"buckets\":[{\"by\":{\"service\":\"checkout\"},\"computes\":{\"c0\":3}}]},\"meta\":{\"elapsed\":7,\"request_id\":\"agg\",\"status\":\"done\"}}");
    fi.override(
        "GET",
        DatadogLogsQuery.INDEXES_PATH,
        200,
        "{\"indexes\":[{\"name\":\"main\",\"filter\":{\"query\":\"*\"},\"num_retention_days\":15,\"daily_limit\":300000000,\"is_rate_limited\":false,\"exclusion_filters\":[]}]}");
    DatadogLogsQuery q = client().query();

    DatadogLogsPage page =
        q.searchLogs(
            SearchLogsRequest.builder()
                .query("service:checkout \"" + run + "\"")
                .from("now-1h")
                .limit(50)
                .indexes("main")
                .build());
    assertEquals(1, page.events().size());
    assertEquals(Optional.of("req-" + run), page.meta().requestId());
    assertTrue(page.nextCursor().isEmpty());
    LogRecord rec = page.records().get(0);
    assertEquals(Severity.ERROR, rec.severity());
    assertEquals(Optional.of("0000000000000000a3ce929d0e0e4736"), rec.traceId());
    assertEquals(Optional.of("00f067aa0ba902b7"), rec.spanId());
    assertEquals("1.2.3", rec.resource().get("service.version"));
    // the search request itself is captured by the container: assert what the SDK sent
    List<FakeIntake.Payload> sent = fi.payloads(DatadogLogsQuery.SEARCH_PATH);
    JsonNode body = Json.tree(sent.get(sent.size() - 1).raw());
    assertEquals("service:checkout \"" + run + "\"", body.at("/filter/query").asText());
    assertEquals("now-1h", body.at("/filter/from").asText());
    assertEquals("now", body.at("/filter/to").asText());
    assertEquals("main", body.at("/filter/indexes/0").asText());
    assertEquals("-timestamp", body.get("sort").asText());
    assertEquals(50, body.at("/page/limit").asInt());
    assertEquals(1, q.searchLogsAsync(SearchLogsRequest.of(run)).join().events().size());

    int n = 0;
    for (DatadogLogEvent e : q.iterateLogs(SearchLogsRequest.builder().query(run).limit(10).build())) {
      assertEquals("stub-1", e.id());
      n++;
    }
    assertEquals(1, n);
    assertEquals(
        1,
        q.search(run, SearchOptions.builder().start(Instant.now().minusSeconds(3600)).build())
            .records()
            .size());
    assertEquals(1, q.searchAsync(run, SearchOptions.defaults()).join().records().size());
    assertEquals(
        "stub " + run,
        q.waitForLog(run, WaitForLogOptions.of(Duration.ofSeconds(10), Duration.ofMillis(200)))
            .body());
    assertEquals("stub " + run, q.waitForLog(run).body());
    assertEquals(
        "stub " + run,
        q.waitForLogAsync(run, WaitForLogOptions.of(Duration.ofSeconds(10), Duration.ofMillis(200)))
            .join()
            .body());

    DatadogAggregateResult agg =
        q.aggregateLogs(
            List.of(DatadogCompute.of("count")),
            List.of(DatadogGroupBy.of("service")),
            AggregateLogsOptions.builder().query(run).build());
    assertEquals(3.0, agg.buckets().get(0).number("c0").getAsDouble());
    assertEquals("checkout", agg.buckets().get(0).by().get("service"));
    assertEquals(
        1,
        q.aggregateLogsAsync(
                List.of(DatadogCompute.of("count")), List.of(), AggregateLogsOptions.defaults())
            .join()
            .buckets()
            .size());
    List<DatadogLogIndex> ix = q.listIndexes();
    assertEquals("main", ix.get(0).name());
    assertEquals(15, ix.get(0).retentionDays().getAsLong());
    assertEquals(List.of("main"), q.listStreams());
    assertEquals(List.of("main"), q.listStreamsAsync().join());
    assertEquals(1, q.listIndexesAsync().join().size());
    assertThrows(UnsupportedException.class, q::listLabels);
    assertThrows(Exception.class, () -> q.listLabelsAsync().join());

    List<LogRecord> got = new CopyOnWriteArrayList<>();
    DatadogLogsQuery.DatadogLogTail tail =
        q.tail(
            run,
            got::add,
            new TailOptions(
                Optional.of(Instant.now().minusSeconds(3600)), Duration.ofMillis(100), null));
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (got.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    tail.stop();
    assertFalse(tail.isRunning());
    assertEquals(1, got.size());
    assertEquals("stub " + run, got.get(0).body());
  }

  @Test
  void exporterLimitsOption() throws Exception {
    String m = run();
    DatadogLogsClient c = client();
    DatadogLogClient lc =
        c.logClient(
            DatadogLogClient.Options.builder()
                .limits(new DatadogLogExporter.Limits(2, 5_000_000, 1_000_000))
                .batchOptions(BatchOptions.builder().flushInterval(Duration.ofHours(1)).build())
                .build());
    for (int i = 0; i < 5; i++) {
      lc.send(LogInput.of("limited " + m));
    }
    lc.flush();
    assertEquals(5, fi.logsWith(m).size());
    assertEquals(3, fi.payloadsWith(m).size());
  }
}
