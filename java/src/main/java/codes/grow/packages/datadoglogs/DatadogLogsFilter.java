package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.ValidationException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filter shared by search and aggregate: query (default {@code *}), time window (default {@code
 * now-15m} to {@code now}), indexes (default {@code ["*"]}) and storage tier (default {@code
 * indexes}).
 *
 * <p>Times are strings as the API takes them: ISO-8601, epoch milliseconds or date math such as
 * {@code now-15m}; {@link Instant} values are sent as ISO-8601 UTC with milliseconds.
 */
public abstract class DatadogLogsFilter {
  /** Default lower time bound (date math). */
  public static final String DEFAULT_FROM = "now-15m";

  /** Default upper time bound. */
  public static final String DEFAULT_TO = "now";

  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final String query;
  private final String from;
  private final String to;
  private final List<String> indexes;
  private final StorageTier storageTier;

  /**
   * Copies the filter fields of a builder.
   *
   * @param b builder
   */
  protected DatadogLogsFilter(Builder<?> b) {
    this.query = b.query == null || b.query.isBlank() ? "*" : b.query;
    this.from = b.from == null ? DEFAULT_FROM : b.from;
    this.to = b.to == null ? DEFAULT_TO : b.to;
    this.indexes = b.indexes.isEmpty() ? List.of("*") : List.copyOf(b.indexes);
    this.storageTier = b.storageTier == null ? StorageTier.INDEXES : b.storageTier;
  }

  /**
   * Formats an instant for {@code from} / {@code to}: ISO-8601 UTC with milliseconds.
   *
   * @param t the instant
   * @return for example {@code 2026-11-18T12:00:00.000Z}
   */
  public static String formatTime(Instant t) {
    return ISO_MILLIS.format(t);
  }

  /**
   * Returns the Datadog search query.
   *
   * @return the query ({@code *} when none was set)
   */
  public String query() {
    return query;
  }

  /**
   * Returns the window start.
   *
   * @return the time string
   */
  public String from() {
    return from;
  }

  /**
   * Returns the window end.
   *
   * @return the time string
   */
  public String to() {
    return to;
  }

  /**
   * Returns the indexes to search.
   *
   * @return immutable list
   */
  public List<String> indexes() {
    return indexes;
  }

  /**
   * Returns the storage tier.
   *
   * @return the tier
   */
  public StorageTier storageTier() {
    return storageTier;
  }

  /**
   * Returns the wire form ({@code filter} object).
   *
   * @return a new map
   */
  public Map<String, Object> filterBody() {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("query", query);
    f.put("from", from);
    f.put("to", to);
    f.put("indexes", indexes);
    f.put("storage_tier", storageTier.wireValue());
    return f;
  }

  /**
   * Builder of the filter fields.
   *
   * @param <B> concrete builder type
   */
  public abstract static class Builder<B extends Builder<B>> {
    private String query;
    private String from;
    private String to;
    private final List<String> indexes = new ArrayList<>();
    private StorageTier storageTier;

    /** Creates an empty builder. */
    protected Builder() {}

    /**
     * Returns this builder.
     *
     * @return this
     */
    protected abstract B self();

    /**
     * Copies the filter fields of an existing filter.
     *
     * @param f filter
     * @return this builder
     */
    protected B copy(DatadogLogsFilter f) {
      query = f.query;
      from = f.from;
      to = f.to;
      indexes.clear();
      indexes.addAll(f.indexes);
      storageTier = f.storageTier;
      return self();
    }

    /**
     * Sets the Datadog search query, for example {@code service:checkout "run-7f3a"}.
     *
     * @param query query (blank means {@code *})
     * @return this builder
     */
    public B query(String query) {
      this.query = query;
      return self();
    }

    /**
     * Sets the window start as ISO-8601, epoch milliseconds or date math.
     *
     * @param from time string
     * @return this builder
     * @throws ValidationException when blank
     */
    public B from(String from) {
      this.from = time(from);
      return self();
    }

    /**
     * Sets the window start.
     *
     * @param from instant
     * @return this builder
     */
    public B from(Instant from) {
      this.from = formatTime(from);
      return self();
    }

    /**
     * Sets the window end as ISO-8601, epoch milliseconds or date math.
     *
     * @param to time string
     * @return this builder
     * @throws ValidationException when blank
     */
    public B to(String to) {
      this.to = time(to);
      return self();
    }

    /**
     * Sets the window end.
     *
     * @param to instant
     * @return this builder
     */
    public B to(Instant to) {
      this.to = formatTime(to);
      return self();
    }

    /**
     * Sets the indexes to search (empty means {@code ["*"]}).
     *
     * @param indexes index names
     * @return this builder
     */
    public B indexes(List<String> indexes) {
      this.indexes.clear();
      this.indexes.addAll(indexes);
      return self();
    }

    /**
     * Sets the indexes to search.
     *
     * @param indexes index names
     * @return this builder
     */
    public B indexes(String... indexes) {
      return indexes(List.of(indexes));
    }

    /**
     * Sets the storage tier (default {@link StorageTier#INDEXES}).
     *
     * @param storageTier tier
     * @return this builder
     */
    public B storageTier(StorageTier storageTier) {
      this.storageTier = storageTier;
      return self();
    }

    private static String time(String value) {
      if (value == null || value.isBlank()) {
        throw new ValidationException("logs.query: time must not be empty");
      }
      return value.trim();
    }
  }
}
