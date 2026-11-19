package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One group-by facet of {@link DatadogLogsQuery#aggregateLogs}.
 *
 * <pre>{@code
 * DatadogGroupBy.of("service");
 * DatadogGroupBy.builder("@http.status_code").limit(5).missing("none").build();
 * }</pre>
 */
public final class DatadogGroupBy {
  private final Map<String, Object> wire;

  private DatadogGroupBy(Builder b) {
    this.wire = Map.copyOf(b.wire);
  }

  /**
   * A group-by on a facet with Datadog defaults.
   *
   * @param facet facet such as {@code service} or {@code @http.status_code}
   * @return the group-by
   */
  public static DatadogGroupBy of(String facet) {
    return builder(facet).build();
  }

  /**
   * Starts a builder.
   *
   * @param facet facet (required)
   * @return a new builder
   * @throws ValidationException when the facet is blank
   */
  public static Builder builder(String facet) {
    return new Builder(facet);
  }

  /**
   * Returns the facet.
   *
   * @return the facet
   */
  public String facet() {
    return (String) wire.get("facet");
  }

  /**
   * Returns the wire form ({@code group_by[]} item).
   *
   * @return a new map
   */
  public Map<String, Object> toWire() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("facet", wire.get("facet"));
    for (String k : new String[] {"limit", "sort", "missing", "total", "histogram"}) {
      if (wire.containsKey(k)) {
        m.put(k, wire.get(k));
      }
    }
    return m;
  }

  /** Builder of {@link DatadogGroupBy}. */
  public static final class Builder {
    private final Map<String, Object> wire = new LinkedHashMap<>();

    private Builder(String facet) {
      if (facet == null || facet.isBlank()) {
        throw new ValidationException("logs.aggregateLogs: groupBy.facet is required");
      }
      wire.put("facet", facet);
    }

    /**
     * Sets the maximum number of buckets (Datadog default 10).
     *
     * @param limit limit
     * @return this builder
     */
    public Builder limit(long limit) {
      wire.put("limit", limit);
      return this;
    }

    /**
     * Sorts buckets alphabetically by facet value.
     *
     * @param order {@code asc} or {@code desc}
     * @return this builder
     */
    public Builder sortAlphabetical(String order) {
      wire.put("sort", Map.of("type", "alphabetical", "order", order));
      return this;
    }

    /**
     * Sorts buckets by a measure.
     *
     * @param aggregation aggregation, e.g. {@code count}
     * @param order {@code asc} or {@code desc}
     * @param metric measure, may be null for {@code count}
     * @return this builder
     */
    public Builder sortByMeasure(String aggregation, String order, String metric) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("type", "measure");
      s.put("aggregation", aggregation);
      s.put("order", order);
      if (metric != null) {
        s.put("metric", metric);
      }
      wire.put("sort", s);
      return this;
    }

    /**
     * Sets the value used for logs without the facet.
     *
     * @param missing a string or a number
     * @return this builder
     */
    public Builder missing(Object missing) {
      wire.put("missing", missing);
      return this;
    }

    /**
     * Adds a total bucket.
     *
     * @param total {@code true} or the label of the total bucket
     * @return this builder
     */
    public Builder total(Object total) {
      wire.put("total", total);
      return this;
    }

    /**
     * Groups a numeric facet into histogram buckets.
     *
     * @param interval bucket width
     * @param min lower bound
     * @param max upper bound
     * @return this builder
     */
    public Builder histogram(double interval, double min, double max) {
      wire.put("histogram", Map.of("interval", interval, "min", min, "max", max));
      return this;
    }

    /**
     * Builds the group-by.
     *
     * @return the group-by
     */
    public DatadogGroupBy build() {
      return new DatadogGroupBy(this);
    }
  }
}
