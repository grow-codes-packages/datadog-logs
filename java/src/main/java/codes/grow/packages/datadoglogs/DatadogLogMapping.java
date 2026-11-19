package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.Json;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.LogRecordJson;
import codes.grow.packages.datadoglogs.logging.Severity;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link LogRecord} to Datadog JSON and back (API.md sections 5.2 and 6.1; DECISIONS 6, 7, 9).
 *
 * <p>Intake entries carry {@code message}, {@code timestamp} (epoch milliseconds), {@code status},
 * {@code service}, {@code hostname}, {@code ddsource}, {@code ddtags}, {@code dd.trace_id} / {@code
 * dd.span_id} (decimal of the lower 64 bits), {@code otel.trace_id} / {@code otel.span_id} (lower
 * hex, lossless), then the record attributes. User attributes named like a reserved key are sent as
 * {@code attr.<name>}.
 */
public final class DatadogLogMapping {
  /** Maximum entries in one intake request (Datadog limit). */
  public static final int MAX_ENTRIES_PER_REQUEST = 1000;

  /** Maximum uncompressed intake payload in bytes, brackets and commas included (5 MB). */
  public static final int MAX_PAYLOAD_BYTES = 5_000_000;

  /** Maximum serialized size of one log entry in bytes (1 MB). */
  public static final int MAX_LOG_BYTES = 1_000_000;

  /** Attribute set to {@code true} on an entry whose message the SDK truncated. */
  public static final String TRUNCATED_ATTRIBUTE = "grow.truncated";

  /** Prefix given to user attributes whose name is reserved (DECISIONS 7). */
  public static final String RESERVED_PREFIX = "attr.";

  /** Top-level keys the SDK writes itself. */
  public static final List<String> RESERVED_KEYS =
      List.of(
          "message",
          "timestamp",
          "status",
          "service",
          "hostname",
          "ddsource",
          "ddtags",
          "dd.trace_id",
          "dd.span_id",
          "otel.trace_id",
          "otel.span_id",
          TRUNCATED_ATTRIBUTE);

  private static final Pattern HEX = Pattern.compile("[0-9a-f]+");
  private static final Pattern DECIMAL = Pattern.compile("\\d+");
  private static final Pattern UNSAFE_TAG =
      Pattern.compile("[\\s,]+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final BigInteger MAX_U64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
  private static final Map<String, Severity> SEVERITY_BY_STATUS = severityByStatus();

  private DatadogLogMapping() {}

  private static Map<String, Severity> severityByStatus() {
    Map<String, Severity> m = new LinkedHashMap<>();
    for (String s :
        new String[] {"emerg", "emergency", "alert", "crit", "critical", "fatal", "panic"}) {
      m.put(s, Severity.FATAL);
    }
    m.put("err", Severity.ERROR);
    m.put("error", Severity.ERROR);
    m.put("warn", Severity.WARN);
    m.put("warning", Severity.WARN);
    for (String s :
        new String[] {"notice", "info", "information", "informational", "ok", "success"}) {
      m.put(s, Severity.INFO);
    }
    m.put("debug", Severity.DEBUG);
    m.put("trace", Severity.TRACE);
    return Map.copyOf(m);
  }

  /**
   * Datadog trace-id form (DECISIONS 6): the decimal string of the lower 64 bits of an
   * OpenTelemetry trace id (up to 32 hex characters).
   *
   * <pre>{@code
   * traceIdToDatadog("4bf92f3577b34da6a3ce929d0e0e4736") // "11803532876627986230"
   * }</pre>
   *
   * @param traceId hex trace id, may be null
   * @return the decimal form, or empty for invalid input
   */
  public static Optional<String> traceIdToDatadog(String traceId) {
    return lower64Decimal(traceId, 32);
  }

  /**
   * Datadog span-id form: the decimal string of a 16-hex OpenTelemetry span id.
   *
   * @param spanId hex span id, may be null
   * @return the decimal form, or empty for invalid input
   */
  public static Optional<String> spanIdToDatadog(String spanId) {
    return lower64Decimal(spanId, 16);
  }

  private static Optional<String> lower64Decimal(String id, int maxLength) {
    if (id == null) {
      return Optional.empty();
    }
    String hex = id.trim().toLowerCase(Locale.ROOT);
    if (hex.isEmpty() || hex.length() > maxLength || !HEX.matcher(hex).matches()) {
      return Optional.empty();
    }
    String low = hex.length() > 16 ? hex.substring(hex.length() - 16) : hex;
    return Optional.of(new BigInteger(low, 16).toString());
  }

  /**
   * Converts a Datadog id (decimal string of up to 64 bits, or already lower hex) to lower hex
   * padded to {@code length} characters.
   *
   * @param id the id: a string or an integral number, may be null
   * @param length 16 (span) or 32 (trace)
   * @return the hex id, or empty for invalid or zero ids
   */
  public static Optional<String> datadogIdToHex(Object id, int length) {
    String text;
    if (id instanceof Long || id instanceof Integer) {
      text = id.toString();
    } else if (id instanceof String) {
      text = ((String) id).trim().toLowerCase(Locale.ROOT);
    } else {
      return Optional.empty();
    }
    String hex;
    if (DECIMAL.matcher(text).matches() && text.length() <= 20) {
      BigInteger n = new BigInteger(text);
      if (n.compareTo(MAX_U64) > 0) {
        return Optional.empty();
      }
      hex = n.toString(16);
    } else if (!text.isEmpty() && HEX.matcher(text).matches() && text.length() <= length) {
      hex = text;
    } else {
      return Optional.empty();
    }
    String padded = "0".repeat(Math.max(0, length - hex.length())) + hex;
    return padded.chars().allMatch(c -> c == '0') ? Optional.empty() : Optional.of(padded);
  }

  /**
   * Datadog {@code status} of a record: its severity text lower-cased, else the canonical severity
   * name.
   *
   * @param record the record
   * @return the status, for example {@code warn}
   */
  public static String datadogStatus(LogRecord record) {
    String text = record.severityText().trim().toLowerCase(Locale.ROOT);
    return text.isEmpty() ? record.severity().text().toLowerCase(Locale.ROOT) : text;
  }

  /**
   * Severity of a Datadog {@code status} (unknown values give {@link Severity#INFO}).
   *
   * @param status status word, may be null
   * @return the severity
   */
  public static Severity severityFromStatus(String status) {
    String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    Severity sev = SEVERITY_BY_STATUS.get(s);
    return sev != null ? sev : Severity.INFO;
  }

  /**
   * Builds the {@code ddtags} value: {@code env:}, {@code version:}, then the tags; comma-joined,
   * de-duplicated, whitespace and commas inside a tag replaced by {@code _}.
   *
   * @param env environment, may be null
   * @param version version, may be null
   * @param tags extra tags
   * @return the value, or empty when there is no tag
   */
  public static Optional<String> buildDdtags(String env, String version, List<String> tags) {
    List<String> out = new ArrayList<>();
    if (env != null) {
      addTag(out, "env:" + env);
    }
    if (version != null) {
      addTag(out, "version:" + version);
    }
    for (String t : tags) {
      addTag(out, t);
    }
    return out.isEmpty() ? Optional.empty() : Optional.of(String.join(",", out));
  }

  private static void addTag(List<String> out, String tag) {
    String clean = UNSAFE_TAG.matcher(tag).replaceAll("_");
    if (!clean.isEmpty() && !out.contains(clean)) {
      out.add(clean);
    }
  }

  private static String stringAttr(Object value) {
    if (value == null) {
      return null;
    }
    String text = value instanceof String ? (String) value : Json.stringify(value);
    return text.isBlank() ? null : text;
  }

  /**
   * Converts a record into a Datadog intake entry. Record resource values win over the config
   * defaults ({@code service}, {@code hostname}, {@code env}, {@code version}); other resource keys
   * are not sent.
   *
   * @param record the record
   * @param config defaults for service, hostname, env, version, tags and source
   * @return the entry, in wire order
   */
  public static Map<String, Object> toDatadogEntry(LogRecord record, DatadogLogsConfig config) {
    Map<String, Object> resource = record.resource();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("message", record.body());
    entry.put("timestamp", record.timestamp().toEpochMilli());
    entry.put("status", datadogStatus(record));
    String service = stringAttr(resource.get("service.name"));
    service = service != null ? service : config.service().orElse(null);
    if (service != null) {
      entry.put("service", service);
    }
    String host = stringAttr(resource.get("host.name"));
    host = host != null ? host : config.hostname().orElse(null);
    if (host != null) {
      entry.put("hostname", host);
    }
    entry.put("ddsource", config.source());
    String env = stringAttr(resource.get("deployment.environment"));
    String version = stringAttr(resource.get("service.version"));
    buildDdtags(
            env != null ? env : config.env().orElse(null),
            version != null ? version : config.version().orElse(null),
            config.tags())
        .ifPresent(t -> entry.put("ddtags", t));
    Optional<String> ddTrace = traceIdToDatadog(record.traceId().orElse(null));
    if (ddTrace.isPresent()) {
      entry.put("dd.trace_id", ddTrace.get());
      Optional<String> ddSpan = spanIdToDatadog(record.spanId().orElse(null));
      ddSpan.ifPresent(s -> entry.put("dd.span_id", s));
      entry.put("otel.trace_id", record.traceId().get());
      if (ddSpan.isPresent()) {
        entry.put("otel.span_id", record.spanId().get());
      }
    }
    for (Map.Entry<String, Object> a : record.attributes().entrySet()) {
      String key = RESERVED_KEYS.contains(a.getKey()) ? RESERVED_PREFIX + a.getKey() : a.getKey();
      entry.put(key, a.getValue());
    }
    return entry;
  }

  /**
   * One serialized entry.
   *
   * @param json the JSON object text
   * @param bytes UTF-8 size of {@code json}
   * @param truncated whether the message was truncated
   */
  public record SerializedEntry(String json, int bytes, boolean truncated) {}

  /**
   * Serializes an entry so it is at most {@code maxBytes} (DECISIONS 7). An oversized entry gets
   * its {@code message} cut at a character boundary and {@code grow.truncated: true}.
   *
   * @param entry the entry from {@link #toDatadogEntry}
   * @param maxBytes maximum UTF-8 size
   * @return the serialized entry, or empty when even an empty message does not fit
   */
  public static Optional<SerializedEntry> serializeEntry(Map<String, Object> entry, int maxBytes) {
    String json = Json.stringify(entry);
    int bytes = utf8Length(json);
    if (bytes <= maxBytes) {
      return Optional.of(new SerializedEntry(json, bytes, false));
    }
    String message = String.valueOf(entry.get("message"));
    Map<String, Object> base = new LinkedHashMap<>(entry);
    base.put("message", "");
    base.put(TRUNCATED_ATTRIBUTE, true);
    int overhead = utf8Length(Json.stringify(base));
    if (overhead > maxBytes) {
      return Optional.empty();
    }
    int budget = maxBytes - overhead;
    int lo = 0;
    int hi = message.length();
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (escapedLength(message.substring(0, mid)) <= budget) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    int end = lo;
    if (end > 0 && Character.isHighSurrogate(message.charAt(end - 1))) {
      end--;
    }
    base.put("message", message.substring(0, end));
    String cut = Json.stringify(base);
    return Optional.of(new SerializedEntry(cut, utf8Length(cut), true));
  }

  /** UTF-8 size of the JSON-escaped string content (never fails on lone surrogates). */
  private static int escapedLength(String s) {
    return utf8Length(new String(JsonStringEncoder.getInstance().quoteAsString(s)));
  }

  /**
   * UTF-8 byte length of a string.
   *
   * @param text text
   * @return the length in bytes
   */
  public static int utf8Length(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Parses one {@code data[]} item of a search response.
   *
   * @param item the JSON item
   * @return the event, or empty when the item is not an object
   */
  public static Optional<DatadogLogEvent> parseLogEvent(JsonNode item) {
    if (item == null || !item.isObject()) {
      return Optional.empty();
    }
    JsonNode attrs = item.path("attributes");
    List<String> tags = new ArrayList<>();
    for (JsonNode t : attrs.path("tags")) {
      if (t.isTextual()) {
        tags.add(t.asText());
      }
    }
    Map<String, Object> custom = new LinkedHashMap<>();
    JsonNode inner = attrs.path("attributes");
    if (inner.isObject()) {
      for (Map.Entry<String, JsonNode> e : inner.properties()) {
        custom.put(e.getKey(), LogRecordJson.toAttrValue(e.getValue()));
      }
    }
    return Optional.of(
        new DatadogLogEvent(
            text(item.get("id")) == null ? "" : text(item.get("id")),
            parseTime(text(attrs.get("timestamp"))),
            text(attrs.get("host")),
            text(attrs.get("service")),
            text(attrs.get("status")),
            text(attrs.get("message")) == null ? "" : text(attrs.get("message")),
            tags,
            custom));
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private static Instant parseTime(String text) {
    if (text == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** Reads {@code a.b} from a flat key {@code "a.b"} or nested objects {@code {a: {b}}}. */
  private static Object dotted(Map<String, Object> attrs, String path) {
    if (attrs.containsKey(path)) {
      return attrs.get(path);
    }
    Object current = attrs;
    for (String part : path.split("\\.")) {
      if (!(current instanceof Map)) {
        return null;
      }
      current = ((Map<?, ?>) current).get(part);
    }
    return current;
  }

  private static String tagValue(List<String> tags, String key) {
    String prefix = key + ":";
    for (String t : tags) {
      if (t.startsWith(prefix)) {
        return t.substring(prefix.length());
      }
    }
    return null;
  }

  /**
   * Normalizes a search event into a {@link LogRecord}: {@code status} to severity, {@code service}
   * / {@code host} / {@code env:} / {@code version:} to the resource, custom attributes plus {@code
   * datadog.log_id} and {@code datadog.tags}; trace ids from {@code otel.trace_id} / {@code
   * otel.span_id}, else from {@code dd.trace_id} / {@code dd.span_id} (decimal or hex; only the
   * lower 64 bits survive the decimal form).
   *
   * @param event the event
   * @return the record
   */
  public static LogRecord eventToLogRecord(DatadogLogEvent event) {
    String status = event.status().orElse("info");
    Map<String, Object> resource = new LinkedHashMap<>();
    event.service().ifPresent(s -> resource.put("service.name", s));
    event.host().ifPresent(h -> resource.put("host.name", h));
    String env = tagValue(event.tags(), "env");
    if (env != null) {
      resource.put("deployment.environment", env);
    }
    String version = tagValue(event.tags(), "version");
    if (version != null) {
      resource.put("service.version", version);
    }
    Map<String, Object> attributes = new LinkedHashMap<>(event.attributes());
    if (!event.id().isEmpty()) {
      attributes.put("datadog.log_id", event.id());
    }
    if (!event.tags().isEmpty()) {
      attributes.put("datadog.tags", event.tags());
    }
    LogRecord.Builder b =
        LogRecord.builder()
            .timestamp(event.timestamp().orElse(Instant.EPOCH))
            .severity(severityFromStatus(status))
            .severityText(status)
            .body(event.message())
            .attributes(attributes)
            .resource(resource);
    Optional<String> traceId =
        datadogIdToHex(dotted(event.attributes(), "otel.trace_id"), 32)
            .or(() -> datadogIdToHex(dotted(event.attributes(), "dd.trace_id"), 32));
    if (traceId.isPresent()) {
      b.traceId(traceId.get());
      datadogIdToHex(dotted(event.attributes(), "otel.span_id"), 16)
          .or(() -> datadogIdToHex(dotted(event.attributes(), "dd.span_id"), 16))
          .ifPresent(b::spanId);
    }
    return b.build();
  }
}
