package codes.grow.packages.datadoglogs;

/** Sort order of {@link DatadogLogsQuery#searchLogs} (Datadog wire values). */
public enum LogsSort {
  /** Oldest logs first ({@code timestamp}). */
  TIMESTAMP_ASC("timestamp"),
  /** Newest logs first ({@code -timestamp}, the default). */
  TIMESTAMP_DESC("-timestamp");

  private final String wire;

  LogsSort(String wire) {
    this.wire = wire;
  }

  /**
   * Returns the wire value.
   *
   * @return {@code timestamp} or {@code -timestamp}
   */
  public String wireValue() {
    return wire;
  }
}
