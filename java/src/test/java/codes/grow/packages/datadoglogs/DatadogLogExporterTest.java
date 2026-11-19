package codes.grow.packages.datadoglogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadoglogs.core.AuthenticationException;
import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.core.RateLimitException;
import codes.grow.packages.datadoglogs.core.TimeoutException;
import codes.grow.packages.datadoglogs.core.UnavailableException;
import codes.grow.packages.datadoglogs.core.ValidationException;
import codes.grow.packages.datadoglogs.logging.ExportOptions;
import codes.grow.packages.datadoglogs.logging.ExportResult;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogLogExporterTest {
  private static final String INTAKE = DatadogLogExporter.INTAKE_PATH;
  private static final ExportOptions GZIP = new ExportOptions(true, Duration.ofSeconds(10));
  private static final ExportOptions PLAIN = new ExportOptions(false, Duration.ofSeconds(10));

  private DdServer server;
  private DatadogLogsClient client;

  @BeforeEach
  void start() {
    server = new DdServer();
    client = DatadogLogsClientTest.client(server);
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  private static List<LogRecord> records(int n, String body) {
    List<LogRecord> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(LogRecord.builder().body(body + " #" + i).build());
    }
    return out;
  }

  @Test
  void limitsAreCapped() {
    DatadogLogExporter.Limits l = new DatadogLogExporter.Limits(5000, 10_000_000, 2_000_000);
    assertThat(l).isEqualTo(DatadogLogExporter.Limits.defaults());
    assertThatThrownBy(() -> new DatadogLogExporter.Limits(0, 1, 1))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("maxEntriesPerRequest");
    assertThat(client.exporter().limits()).isEqualTo(DatadogLogExporter.Limits.defaults());
  }

  @Test
  void encodeSplitsByEntriesAndBytes() {
    DatadogLogExporter.EncodedBatch b = client.exporter().encode(records(2500, "e"));
    assertThat(b.payloads())
        .extracting(DatadogLogExporter.IntakePayload::entries)
        .containsExactly(1000, 1000, 500);
    assertThat(b.oversized()).isZero();
    for (DatadogLogExporter.IntakePayload p : b.payloads()) {
      assertThat(p.bytes()).isEqualTo(DatadogLogMapping.utf8Length(p.body()));
      assertThat(p.body()).startsWith("[{").endsWith("}]");
    }
    DatadogLogExporter small =
        new DatadogLogExporter(
            new DatadogHttp(client.transport(), client.config()),
            new DatadogLogExporter.Limits(1000, 400, 300));
    DatadogLogExporter.EncodedBatch bytes = small.encode(records(10, "x".repeat(100)));
    assertThat(bytes.payloads()).allSatisfy(p -> assertThat(p.bytes()).isLessThanOrEqualTo(400));
    assertThat(bytes.payloads().stream().mapToInt(DatadogLogExporter.IntakePayload::entries).sum())
        .isEqualTo(10);
    DatadogLogExporter.EncodedBatch trunc = small.encode(records(1, "y".repeat(1000)));
    assertThat(trunc.truncated()).isEqualTo(1);
    assertThat(trunc.payloads().get(0).bytes()).isLessThanOrEqualTo(302);
    assertThat(small.encode(List.of()).payloads()).isEmpty();
  }

  @Test
  void exportSendsGzipWithApiKeyOnly() {
    server.always(INTAKE, 202, "{}");
    ExportResult r = client.exporter().export(records(3, "hello"), GZIP).join();
    assertThat(r.accepted()).isEqualTo(3);
    assertThat(r.error()).isEmpty();
    DdServer.Req req = server.requests(INTAKE).get(0);
    assertThat(req.method()).isEqualTo("POST");
    assertThat(req.header("Content-Encoding")).isEqualTo("gzip");
    assertThat(req.header("Content-Type")).isEqualTo("application/json");
    assertThat(req.header("DD-API-KEY")).isEqualTo(DatadogLogsClientTest.API_KEY);
    assertThat(req.header("DD-APPLICATION-KEY")).isNull();
    JsonNode body = req.json();
    assertThat(body.isArray()).isTrue();
    assertThat(body).hasSize(3);
    assertThat(body.get(0).get("message").asText()).isEqualTo("hello #0");
    assertThat(body.get(0).get("timestamp").isIntegralNumber()).isTrue();

    client.exporter().sendPayload("[{\"message\":\"plain\"}]", false);
    DdServer.Req plain = server.requests(INTAKE).get(1);
    assertThat(plain.header("Content-Encoding")).isNull();
    assertThat(plain.body()).isEqualTo("[{\"message\":\"plain\"}]");
    assertThat(client.exporter().export(records(1, "p"), PLAIN).join().accepted()).isEqualTo(1);
    assertThat(client.exporter().shutdown().join()).isNull();
  }

  @Test
  void exportStopsAtFirstFailingRequest() {
    server.on(INTAKE, 202, "{}").fixture(INTAKE, "send.400_injected");
    ExportResult r = client.exporter().export(records(1500, "chunk"), GZIP).join();
    assertThat(r.accepted()).isEqualTo(1000);
    assertThat(r.rejected()).isEqualTo(500);
    assertThat(r.error()).containsInstanceOf(ValidationException.class);
  }

  @Test
  void oversizedRecordsAreRejected() {
    server.always(INTAKE, 202, "{}");
    DatadogLogExporter tiny =
        new DatadogLogExporter(
            new DatadogHttp(client.transport(), client.config()),
            new DatadogLogExporter.Limits(1000, 5_000_000, 120));
    List<LogRecord> recs = new ArrayList<>(records(1, "ok"));
    recs.add(LogRecord.builder().body("x").attribute("big", "z".repeat(500)).build());
    ExportResult r = tiny.export(recs, GZIP).join();
    assertThat(r.accepted()).isEqualTo(1);
    assertThat(r.rejected()).isEqualTo(1);
    assertThat(r.error().get()).isInstanceOf(ValidationException.class).hasMessageContaining("120");
  }

  private DatadogLogsException exportError() {
    ExportResult r = client.exporter().export(records(1, "fault"), GZIP).join();
    assertThat(r.accepted()).isZero();
    assertThat(r.rejected()).isEqualTo(1);
    return r.error().orElseThrow();
  }

  @Test
  void intakeErrorMapping() {
    server
        .fixture(INTAKE, "send.400_injected")
        .fixture(INTAKE, "send.401_injected")
        .fixture(INTAKE, "send.403_invalid_key")
        .fixture(INTAKE, "send.413_injected");
    assertThat(exportError()).isInstanceOf(ValidationException.class);
    assertThat(exportError()).isInstanceOf(AuthenticationException.class);
    DatadogLogsException forbidden = exportError();
    assertThat(forbidden).isInstanceOf(AuthenticationException.class);
    assertThat(forbidden.httpStatus()).hasValue(403);
    assertThat(forbidden.getMessage()).contains("API key is missing or invalid");
    assertThat(exportError()).isInstanceOf(ValidationException.class);
    assertThat(server.requests(INTAKE)).hasSize(4);
  }

  @Test
  void intakeRetriesTransientFailures() {
    server
        .fixture(INTAKE, "send.429_injected")
        .fixture(INTAKE, "send.500_injected")
        .fixture(INTAKE, "send.503_injected")
        .fixture(INTAKE, "send.datadog_202");
    ExportResult r = client.exporter().export(records(1, "retry"), GZIP).join();
    assertThat(r.accepted()).isEqualTo(1);
    assertThat(server.requests(INTAKE)).hasSize(4);
    server.always(INTAKE, 503, "{\"errors\":[\"Service Unavailable\"]}");
    assertThat(exportError()).isInstanceOf(UnavailableException.class);
    server.always(INTAKE, 429, "{\"errors\":[\"Too many requests\"]}");
    assertThat(exportError()).isInstanceOf(RateLimitException.class);
    server.always(INTAKE, 408, "{}");
    assertThat(exportError()).isInstanceOf(TimeoutException.class);
  }

  @Test
  void sendPayloadThrowsMappedErrors() {
    server.fixture(INTAKE, "send.403_missing_key");
    assertThatThrownBy(() -> client.exporter().sendPayload("[]", true))
        .isInstanceOf(AuthenticationException.class);
  }
}
