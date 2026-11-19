package codes.grow.packages.datadoglogs;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Input of {@link DatadogLogsQuery#searchLogs} and {@link DatadogLogsQuery#iterateLogs}.
 *
 * <pre>{@code
 * SearchLogsRequest req = SearchLogsRequest.builder()
 *     .query("service:checkout \"run-7f3a\"")
 *     .from("now-1h")
 *     .limit(50)
 *     .build();
 * }</pre>
 */
public final class SearchLogsRequest extends DatadogLogsFilter {
  private final Integer limit;
  private final Integer pageSize;
  private final LogsSort sort;
  private final String cursor;

  private SearchLogsRequest(Builder b) {
    super(b);
    this.limit = b.limit;
    this.pageSize = b.pageSize;
    this.sort = b.sort;
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
   * A request for {@code query} with every other field at its default.
   *
   * @param query Datadog search query
   * @return the request
   */
  public static SearchLogsRequest of(String query) {
    return builder().query(query).build();
  }

  /**
   * Returns a builder with this request's values.
   *
   * @return a new builder
   */
  public Builder toBuilder() {
    Builder b = new Builder().copy(this);
    b.limit = limit;
    b.pageSize = pageSize;
    b.sort = sort;
    b.cursor = cursor;
    return b;
  }

  /**
   * Returns the limit: records in the page for {@code searchLogs} (1..5000, default 100), total
   * records for {@code iterateLogs} (default all).
   *
   * @return the limit, or empty
   */
  public OptionalInt limit() {
    return limit == null ? OptionalInt.empty() : OptionalInt.of(limit);
  }

  /**
   * Returns the page size of {@code iterateLogs} (1..5000, default min(limit, 1000)).
   *
   * @return the page size, or empty
   */
  public OptionalInt pageSize() {
    return pageSize == null ? OptionalInt.empty() : OptionalInt.of(pageSize);
  }

  /**
   * Returns the sort order.
   *
   * @return the order (default newest first)
   */
  public LogsSort sort() {
    return sort;
  }

  /**
   * Returns the cursor that continues a previous search.
   *
   * @return the cursor, or empty
   */
  public Optional<String> cursor() {
    return Optional.ofNullable(cursor);
  }

  /** Builder of {@link SearchLogsRequest}. */
  public static final class Builder extends DatadogLogsFilter.Builder<Builder> {
    private Integer limit;
    private Integer pageSize;
    private LogsSort sort = LogsSort.TIMESTAMP_DESC;
    private String cursor;

    private Builder() {}

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * Sets the limit (see {@link SearchLogsRequest#limit()}).
     *
     * @param limit limit
     * @return this builder
     */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Sets the page size of {@code iterateLogs}.
     *
     * @param pageSize records per request
     * @return this builder
     */
    public Builder pageSize(int pageSize) {
      this.pageSize = pageSize;
      return this;
    }

    /**
     * Sets the sort order (default {@link LogsSort#TIMESTAMP_DESC}).
     *
     * @param sort order
     * @return this builder
     */
    public Builder sort(LogsSort sort) {
      this.sort = Objects.requireNonNull(sort, "sort");
      return this;
    }

    /**
     * Sets the cursor from {@link DatadogLogsPage#nextCursor()}; send the same filter and sort.
     *
     * @param cursor cursor
     * @return this builder
     */
    public Builder cursor(String cursor) {
      this.cursor = cursor;
      return this;
    }

    /**
     * Builds the request.
     *
     * @return the request
     */
    public SearchLogsRequest build() {
      return new SearchLogsRequest(this);
    }
  }
}
