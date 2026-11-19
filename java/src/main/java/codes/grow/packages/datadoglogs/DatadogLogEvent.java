package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.logging.LogRecord;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One log event returned by the Datadog search API, flattened ({@code data[]} of {@code POST
 * /api/v2/logs/events/search}). Immutable.
 */
public final class DatadogLogEvent {
  private final String id;
  private final Instant timestamp;
  private final String host;
  private final String service;
  private final String status;
  private final String message;
  private final List<String> tags;
  private final Map<String, Object> attributes;

  /**
   * Creates an event; collections are copied.
   *
   * @param id Datadog log id
   * @param timestamp event time, may be null
   * @param host reserved {@code host}, may be null
   * @param service reserved {@code service}, may be null
   * @param status reserved {@code status}, may be null
   * @param message message text
   * @param tags tags such as {@code env:test}
   * @param attributes custom attributes ({@code data[].attributes.attributes}), nested as returned
   */
  public DatadogLogEvent(
      String id,
      Instant timestamp,
      String host,
      String service,
      String status,
      String message,
      List<String> tags,
      Map<String, Object> attributes) {
    this.id = Objects.requireNonNull(id, "id");
    this.timestamp = timestamp;
    this.host = host;
    this.service = service;
    this.status = status;
    this.message = Objects.requireNonNull(message, "message");
    this.tags = tags == null ? List.of() : List.copyOf(tags);
    this.attributes =
        attributes == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
  }

  /**
   * Returns the Datadog log id.
   *
   * @return the id (empty string when absent)
   */
  public String id() {
    return id;
  }

  /**
   * Returns the event time.
   *
   * @return the time, or empty when Datadog sent none
   */
  public Optional<Instant> timestamp() {
    return Optional.ofNullable(timestamp);
  }

  /**
   * Returns the reserved {@code host}.
   *
   * @return the host, or empty
   */
  public Optional<String> host() {
    return Optional.ofNullable(host);
  }

  /**
   * Returns the reserved {@code service}.
   *
   * @return the service, or empty
   */
  public Optional<String> service() {
    return Optional.ofNullable(service);
  }

  /**
   * Returns the reserved {@code status}.
   *
   * @return the status, or empty
   */
  public Optional<String> status() {
    return Optional.ofNullable(status);
  }

  /**
   * Returns the message text.
   *
   * @return the message
   */
  public String message() {
    return message;
  }

  /**
   * Returns the tags.
   *
   * @return immutable list
   */
  public List<String> tags() {
    return tags;
  }

  /**
   * Returns the custom attributes as returned (nested objects, for example {@code
   * {"dd":{"trace_id":"…"}}}).
   *
   * @return immutable map
   */
  public Map<String, Object> attributes() {
    return attributes;
  }

  /**
   * Normalizes the event into a log record (see {@link DatadogLogMapping#eventToLogRecord}).
   *
   * @return the record
   */
  public LogRecord toLogRecord() {
    return DatadogLogMapping.eventToLogRecord(this);
  }

  @Override
  public String toString() {
    return "DatadogLogEvent{id="
        + id
        + ", timestamp="
        + timestamp
        + ", service="
        + service
        + ", status="
        + status
        + ", message="
        + message
        + "}";
  }
}
