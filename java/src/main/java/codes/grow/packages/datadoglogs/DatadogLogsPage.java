package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.logging.LogRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of {@link DatadogLogsQuery#searchLogs}.
 *
 * @param events events as returned by Datadog (flattened), in the requested order
 * @param meta response metadata (next cursor, status, warnings, request id)
 */
public record DatadogLogsPage(List<DatadogLogEvent> events, DatadogQueryMeta meta) {
  /**
   * Creates a page; the list is copied.
   *
   * @param events events
   * @param meta metadata
   */
  public DatadogLogsPage {
    events = List.copyOf(events);
    Objects.requireNonNull(meta, "meta");
  }

  /**
   * Returns the events normalized to log records.
   *
   * @return a new list
   */
  public List<LogRecord> records() {
    return events.stream().map(DatadogLogMapping::eventToLogRecord).toList();
  }

  /**
   * Returns the cursor of the next page; pass it back with the same filter and sort.
   *
   * @return the cursor, or empty on the last page
   */
  public Optional<String> nextCursor() {
    return meta.nextCursor();
  }
}
