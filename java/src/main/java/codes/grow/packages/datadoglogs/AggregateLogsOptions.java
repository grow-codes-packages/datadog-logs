package codes.grow.packages.datadoglogs;

import java.util.Optional;

/** Filter and cursor of {@link DatadogLogsQuery#aggregateLogs}. */
public final class AggregateLogsOptions extends DatadogLogsFilter {
  private final String cursor;

  private AggregateLogsOptions(Builder b) {
    super(b);
    this.cursor = b.cursor == null || b.cursor.isEmpty() ? null : b.cursor;
  }

  /**
   * Starts a builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Options with every field at its default (all logs of the last 15 minutes).
   *
   * @return the options
   */
  public static AggregateLogsOptions defaults() {
    return builder().build();
  }

  /**
   * Returns the cursor from {@link DatadogAggregateResult#nextCursor()}.
   *
   * @return the cursor, or empty
   */
  public Optional<String> cursor() {
    return Optional.ofNullable(cursor);
  }

  /** Builder of {@link AggregateLogsOptions}. */
  public static final class Builder extends DatadogLogsFilter.Builder<Builder> {
    private String cursor;

    private Builder() {}

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * Sets the cursor of the next page of buckets.
     *
     * @param cursor cursor
     * @return this builder
     */
    public Builder cursor(String cursor) {
      this.cursor = cursor;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public AggregateLogsOptions build() {
      return new AggregateLogsOptions(this);
    }
  }
}
