package codes.grow.packages.datadoglogs;

import static org.assertj.core.api.Assertions.assertThat;

import codes.grow.packages.datadoglogs.core.Json;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatadogLogMappingTest {
  private static DatadogLogsConfig config() {
    return DatadogLogsConfig.builder()
        .apiKey("k")
        .service("cfg-svc")
        .hostname("cfg-host")
        .env("cfg-env")
        .version("1.0")
        .tags(List.of("team:sdk", "lang:java"))
        .build();
  }

  @Test
  void traceIds() {
    assertThat(DatadogLogMapping.traceIdToDatadog("4bf92f3577b34da6a3ce929d0e0e4736"))
        .contains("11803532876627986230");
    assertThat(DatadogLogMapping.traceIdToDatadog("a3ce929d0e0e4736"))
        .contains("11803532876627986230");
    assertThat(DatadogLogMapping.spanIdToDatadog("00f067aa0ba902b7")).contains("67667974448284343");
    assertThat(DatadogLogMapping.traceIdToDatadog(null)).isEmpty();
    assertThat(DatadogLogMapping.traceIdToDatadog("  ")).isEmpty();
    assertThat(DatadogLogMapping.traceIdToDatadog("xyz")).isEmpty();
    assertThat(DatadogLogMapping.spanIdToDatadog("0".repeat(17))).isEmpty();

    assertThat(DatadogLogMapping.datadogIdToHex("11803532876627986230", 32))
        .contains("0000000000000000a3ce929d0e0e4736");
    assertThat(DatadogLogMapping.datadogIdToHex(67667974448284343L, 16))
        .contains("00f067aa0ba902b7");
    assertThat(DatadogLogMapping.datadogIdToHex(5, 16)).contains("0000000000000005");
    assertThat(DatadogLogMapping.datadogIdToHex("4BF92F3577B34DA6A3CE929D0E0E4736", 32))
        .contains("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(DatadogLogMapping.datadogIdToHex("99999999999999999999", 32)).isEmpty();
    assertThat(DatadogLogMapping.datadogIdToHex("0", 16)).isEmpty();
    assertThat(DatadogLogMapping.datadogIdToHex("", 16)).isEmpty();
    assertThat(DatadogLogMapping.datadogIdToHex("zz", 16)).isEmpty();
    assertThat(DatadogLogMapping.datadogIdToHex("a".repeat(17), 16)).isEmpty();
    assertThat(DatadogLogMapping.datadogIdToHex(1.5, 16)).isEmpty();
    assertThat(DatadogLogMapping.datadogIdToHex(null, 16)).isEmpty();
  }

  @Test
  void statusAndSeverity() {
    LogRecord warn = LogRecord.builder().severity(Severity.WARN).build();
    assertThat(DatadogLogMapping.datadogStatus(warn)).isEqualTo("warn");
    LogRecord vendor =
        LogRecord.builder().severity(Severity.ERROR).severityText(" CRITICAL ").build();
    assertThat(DatadogLogMapping.datadogStatus(vendor)).isEqualTo("critical");
    LogRecord blank = LogRecord.builder().severity(Severity.DEBUG).severityText("  ").build();
    assertThat(DatadogLogMapping.datadogStatus(blank)).isEqualTo("debug");
    assertThat(DatadogLogMapping.severityFromStatus("emerg")).isEqualTo(Severity.FATAL);
    assertThat(DatadogLogMapping.severityFromStatus("Warning")).isEqualTo(Severity.WARN);
    assertThat(DatadogLogMapping.severityFromStatus("err")).isEqualTo(Severity.ERROR);
    assertThat(DatadogLogMapping.severityFromStatus("ok")).isEqualTo(Severity.INFO);
    assertThat(DatadogLogMapping.severityFromStatus("debug")).isEqualTo(Severity.DEBUG);
    assertThat(DatadogLogMapping.severityFromStatus("trace")).isEqualTo(Severity.TRACE);
    assertThat(DatadogLogMapping.severityFromStatus("weird")).isEqualTo(Severity.INFO);
    assertThat(DatadogLogMapping.severityFromStatus(null)).isEqualTo(Severity.INFO);
  }

  @Test
  void ddtags() {
    assertThat(DatadogLogMapping.buildDdtags(null, null, List.of())).isEmpty();
    assertThat(DatadogLogMapping.buildDdtags("p r", "1", List.of("a:1", "a:1", "b, c", "")))
        .contains("env:p_r,version:1,a:1,b_c");
  }

  @Test
  void entryFromConfigDefaults() {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("service", "user-value");
    attrs.put("user", Map.of("id", 42));
    LogRecord r =
        LogRecord.builder()
            .timestamp(Instant.ofEpochMilli(1795033887895L))
            .severity(Severity.WARN)
            .body("hello")
            .attributes(attrs)
            .build();
    Map<String, Object> e = DatadogLogMapping.toDatadogEntry(r, config());
    assertThat(List.copyOf(e.keySet()))
        .containsExactly(
            "message",
            "timestamp",
            "status",
            "service",
            "hostname",
            "ddsource",
            "ddtags",
            "attr.service",
            "user");
    assertThat(e)
        .containsEntry("timestamp", 1795033887895L)
        .containsEntry("status", "warn")
        .containsEntry("service", "cfg-svc")
        .containsEntry("hostname", "cfg-host")
        .containsEntry("ddsource", "grow-sdk")
        .containsEntry("ddtags", "env:cfg-env,version:1.0,team:sdk,lang:java")
        .containsEntry("attr.service", "user-value");
  }

  @Test
  void entryFromResourceAndTraceIds() {
    LogRecord r =
        LogRecord.builder()
            .body("x")
            .resource(
                Map.of(
                    "service.name", "checkout",
                    "host.name", "h",
                    "deployment.environment", "test",
                    "service.version", 2))
            .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
            .spanId("00f067aa0ba902b7")
            .build();
    Map<String, Object> e =
        DatadogLogMapping.toDatadogEntry(r, DatadogLogsConfig.builder().apiKey("k").build());
    assertThat(e)
        .containsEntry("service", "checkout")
        .containsEntry("hostname", "h")
        .containsEntry("ddtags", "env:test,version:2")
        .containsEntry("dd.trace_id", "11803532876627986230")
        .containsEntry("dd.span_id", "67667974448284343")
        .containsEntry("otel.trace_id", "4bf92f3577b34da6a3ce929d0e0e4736")
        .containsEntry("otel.span_id", "00f067aa0ba902b7");
    LogRecord noSpan =
        LogRecord.builder().body("x").traceId("4bf92f3577b34da6a3ce929d0e0e4736").build();
    Map<String, Object> ns =
        DatadogLogMapping.toDatadogEntry(noSpan, DatadogLogsConfig.builder().apiKey("k").build());
    assertThat(ns)
        .containsKey("dd.trace_id")
        .doesNotContainKey("dd.span_id")
        .doesNotContainKey("otel.span_id");
    assertThat(ns).doesNotContainKeys("service", "hostname", "ddtags");
    LogRecord blankRes =
        LogRecord.builder().body("x").resource(Map.of("service.name", " ")).build();
    assertThat(DatadogLogMapping.toDatadogEntry(blankRes, config()))
        .containsEntry("service", "cfg-svc");
  }

  @Test
  void serializeTruncatesAtCharacterBoundary() {
    Map<String, Object> small = new LinkedHashMap<>(Map.of("message", "hi"));
    DatadogLogMapping.SerializedEntry s =
        DatadogLogMapping.serializeEntry(small, 100).orElseThrow();
    assertThat(s.truncated()).isFalse();
    assertThat(s.json()).isEqualTo("{\"message\":\"hi\"}");

    Map<String, Object> big = new LinkedHashMap<>();
    big.put("message", "ab\"\n日本語😀".repeat(50));
    big.put("k", "v");
    DatadogLogMapping.SerializedEntry t = DatadogLogMapping.serializeEntry(big, 200).orElseThrow();
    assertThat(t.truncated()).isTrue();
    assertThat(t.bytes())
        .isLessThanOrEqualTo(200)
        .isEqualTo(DatadogLogMapping.utf8Length(t.json()));
    JsonNode parsed = Json.tree(t.json().getBytes(StandardCharsets.UTF_8));
    assertThat(parsed.get("grow.truncated").asBoolean()).isTrue();
    assertThat(parsed.get("k").asText()).isEqualTo("v");
    String msg = parsed.get("message").asText();
    assertThat(msg).isNotEmpty();
    assertThat(Character.isHighSurrogate(msg.charAt(msg.length() - 1))).isFalse();

    // a surrogate pair that would be split is dropped whole
    for (int max = 40; max < 80; max++) {
      Map<String, Object> emoji = new LinkedHashMap<>(Map.of("message", "😀".repeat(30)));
      String m =
          Json.tree(
                  DatadogLogMapping.serializeEntry(emoji, max)
                      .orElseThrow()
                      .json()
                      .getBytes(StandardCharsets.UTF_8))
              .get("message")
              .asText();
      assertThat(m.length() % 2).isZero();
    }

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("message", "x".repeat(100));
    meta.put("big", "y".repeat(500));
    assertThat(DatadogLogMapping.serializeEntry(meta, 200)).isEmpty();
  }

  @Test
  void parseAndNormalizeEvents() {
    JsonNode body = Fixtures.load("searchLogs.page1").get("response").get("body");
    DatadogLogEvent e = DatadogLogMapping.parseLogEvent(body.get("data").get(0)).orElseThrow();
    assertThat(e.id()).startsWith("AQAAAZq0001");
    assertThat(e.timestamp()).contains(Instant.parse("2026-11-18T12:01:02.345Z"));
    assertThat(e.host()).contains("host-a");
    assertThat(e.service()).contains("checkout");
    assertThat(e.status()).contains("info");
    assertThat(e.message()).isEqualTo("user signed in run-7f3a #2");
    assertThat(e.tags()).contains("env:test");
    assertThat(e.toString()).contains("checkout");
    LogRecord r = e.toLogRecord();
    assertThat(r.severity()).isEqualTo(Severity.INFO);
    assertThat(r.severityText()).isEqualTo("info");
    assertThat(r.resource())
        .containsEntry("service.name", "checkout")
        .containsEntry("host.name", "host-a")
        .containsEntry("deployment.environment", "test")
        .containsEntry("service.version", "1.2.3");
    assertThat(r.attributes()).containsKey("datadog.log_id").containsKey("datadog.tags");
    assertThat(r.traceId()).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(r.spanId()).contains("00f067aa0ba902b7");

    assertThat(DatadogLogMapping.parseLogEvent(null)).isEmpty();
    assertThat(DatadogLogMapping.parseLogEvent(Json.tree("[1]".getBytes(StandardCharsets.UTF_8))))
        .isEmpty();
    DatadogLogEvent bare =
        DatadogLogMapping.parseLogEvent(
                Json.tree(
                    "{\"attributes\":{\"timestamp\":\"bad\",\"tags\":[1,\"x:y\"],\"attributes\":{\"otel.trace_id\":\"11803532876627986230\",\"otel.span_id\":5}}}"
                        .getBytes(StandardCharsets.UTF_8)))
            .orElseThrow();
    assertThat(bare.id()).isEmpty();
    assertThat(bare.message()).isEmpty();
    assertThat(bare.timestamp()).isEmpty();
    assertThat(bare.tags()).containsExactly("x:y");
    LogRecord br = bare.toLogRecord();
    assertThat(br.timestamp()).isEqualTo(Instant.EPOCH);
    assertThat(br.severityText()).isEqualTo("info");
    assertThat(br.traceId()).contains("0000000000000000a3ce929d0e0e4736");
    assertThat(br.spanId()).contains("0000000000000005");
    assertThat(br.attributes()).doesNotContainKey("datadog.log_id");

    DatadogLogEvent none = new DatadogLogEvent("", null, null, null, null, "m", null, null);
    LogRecord nr = none.toLogRecord();
    assertThat(nr.traceId()).isEmpty();
    assertThat(nr.resource()).isEmpty();
    assertThat(nr.attributes()).isEmpty();
    DatadogLogEvent nested =
        new DatadogLogEvent(
            "id", null, null, null, "error", "m", List.of(), Map.of("dd", "not-a-map"));
    assertThat(nested.toLogRecord().traceId()).isEmpty();
  }
}
