package codes.grow.packages.datadoglogs.integration;

import codes.grow.packages.datadoglogs.core.Json;
import codes.grow.packages.datadoglogs.core.UnavailableException;
import codes.grow.packages.datadoglogs.logging.LogQueryClient;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.LogRecordJson;
import codes.grow.packages.datadoglogs.logging.LogSearchResult;
import codes.grow.packages.datadoglogs.logging.SearchOptions;
import codes.grow.packages.datadoglogs.logging.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * The datadog/fakeintake harness endpoints (not Datadog APIs, API.md section 7): raw payload
 * read-back and response overrides.
 */
final class FakeIntake {
  static final String LOGS = "/api/v2/logs";

  /** One captured request body, decoded. */
  record Payload(byte[] raw, List<JsonNode> logs) {}

  private final String base;
  private final HttpClient http = HttpClient.newHttpClient();

  FakeIntake(Map<String, String> env) {
    String url = env.getOrDefault("FAKEINTAKE_URL", env.get("GROW_DATADOG_LOGS_URL"));
    this.base = url.replaceAll("/+$", "");
  }

  private String get(String path) throws IOException, InterruptedException {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) {
      throw new IOException("fakeintake " + path + ": HTTP " + r.statusCode());
    }
    return r.body();
  }

  /** Every captured body of {@code endpoint}, decoded from the raw view. */
  List<Payload> payloads(String endpoint) throws IOException, InterruptedException {
    JsonNode body =
        Json.tree(
            get("/fakeintake/payloads?endpoint=" + URLEncoder.encode(endpoint, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
    List<Payload> out = new ArrayList<>();
    for (JsonNode p : body.path("payloads")) {
      byte[] data = Base64.getDecoder().decode(p.path("data").asText());
      String encoding = p.path("encoding").asText();
      byte[] raw = data;
      if (encoding.equals("gzip")) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
          raw = in.readAllBytes();
        }
      } else if (encoding.equals("deflate")) {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
          raw = in.readAllBytes();
        }
      }
      List<JsonNode> logs = new ArrayList<>();
      try {
        JsonNode json = Json.tree(raw);
        if (json.isArray()) {
          json.forEach(logs::add);
        } else if (json.isObject()) {
          logs.add(json);
        }
      } catch (RuntimeException notJson) {
        // empty or non-JSON body
      }
      out.add(new Payload(raw, logs));
    }
    return out;
  }

  /** Captured intake logs whose message contains {@code marker}. */
  List<JsonNode> logsWith(String marker) throws IOException, InterruptedException {
    List<JsonNode> out = new ArrayList<>();
    for (Payload p : payloads(LOGS)) {
      for (JsonNode l : p.logs()) {
        if (l.path("message").asText().contains(marker)) {
          out.add(l);
        }
      }
    }
    return out;
  }

  /** Captured intake payloads that hold at least one log with {@code marker}. */
  List<Payload> payloadsWith(String marker) throws IOException, InterruptedException {
    List<Payload> out = new ArrayList<>();
    for (Payload p : payloads(LOGS)) {
      if (p.logs().stream().anyMatch(l -> l.path("message").asText().contains(marker))) {
        out.add(p);
      }
    }
    return out;
  }

  /** Makes {@code method endpoint} answer {@code status}/{@code body}; payloads stay captured. */
  void override(String method, String endpoint, int status, String body)
      throws IOException, InterruptedException {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("method", method);
    spec.put("endpoint", endpoint);
    spec.put("status_code", status);
    spec.put("content_type", "application/json");
    spec.put("body", Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/fakeintake/configure/override"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(spec)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) {
      throw new IOException("override " + method + " " + endpoint + ": HTTP " + r.statusCode());
    }
  }

  /** Restores the bootstrap overrides (intake 202 {}, validate 200 {"valid":true}). */
  void restoreDefaults() throws IOException, InterruptedException {
    override("POST", LOGS, 202, "{}");
    override("GET", "/api/v1/validate", 200, "{\"valid\":true}");
  }

  /** A captured Datadog log turned back into a LogRecord (test-side reverse mapping). */
  static LogRecord toRecord(JsonNode l) {
    LogRecord.Builder b = LogRecord.builder();
    Map<String, Object> attrs = new LinkedHashMap<>();
    Map<String, Object> resource = new LinkedHashMap<>();
    l.properties()
        .forEach(
            e -> {
              JsonNode v = e.getValue();
              switch (e.getKey()) {
                case "message":
                  b.body(v.asText());
                  break;
                case "status":
                  b.severityText(v.asText()).severity(Severity.parse(v.asText()).orElse(Severity.INFO));
                  break;
                case "timestamp":
                  b.timestamp(Instant.ofEpochMilli(v.asLong()));
                  break;
                case "service":
                  resource.put("service.name", v.asText());
                  break;
                case "hostname":
                  resource.put("host.name", v.asText());
                  break;
                case "otel.trace_id":
                  b.traceId(v.asText());
                  break;
                case "otel.span_id":
                  b.spanId(v.asText());
                  break;
                default:
                  attrs.put(e.getKey(), LogRecordJson.toAttrValue(v));
              }
            });
    return b.attributes(attrs).resource(resource).build();
  }

  /**
   * A LogQueryClient over the fakeintake capture: search returns the captured intake logs whose
   * message contains every term of the query (quotes ignored). It lets the logging conformance
   * scenarios assert send then read back against the real container; Datadog's own search API is
   * not available there (unit tests + live job).
   */
  LogQueryClient query() {
    return (query, options) -> search(query, options);
  }

  private LogSearchResult search(String query, SearchOptions options) {
    String[] terms = query.replace('"', ' ').trim().split("\\s+");
    List<LogRecord> out = new ArrayList<>();
    try {
      for (Payload p : payloads(LOGS)) {
        for (JsonNode l : p.logs()) {
          String msg = l.path("message").asText();
          boolean match = true;
          for (String t : terms) {
            if (!msg.contains(t)) {
              match = false;
              break;
            }
          }
          if (match) {
            out.add(toRecord(l));
          }
        }
      }
    } catch (IOException e) {
      throw new UnavailableException("fakeintake: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException("fakeintake: interrupted", e);
    }
    return LogSearchResult.of(out);
  }
}
