package codes.grow.packages.datadoglogs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One bucket of {@link DatadogLogsQuery#aggregateLogs}.
 *
 * @param by facet values of the bucket (empty without group-by); values are {@code String}, {@code
 *     Long} or {@code Double}
 * @param computes results keyed {@code c0}, {@code c1}, … in compute order; values are {@code
 *     Double}, {@code String} or {@code List<DatadogTimeseriesPoint>}
 */
public record DatadogBucket(Map<String, Object> by, Map<String, Object> computes) {
  /**
   * Creates a bucket; maps are copied.
   *
   * @param by facet values
   * @param computes results
   */
  public DatadogBucket {
    by = Collections.unmodifiableMap(new LinkedHashMap<>(by));
    computes = Collections.unmodifiableMap(new LinkedHashMap<>(computes));
  }

  /**
   * Returns a numeric compute result.
   *
   * @param key compute key such as {@code c0}
   * @return the number, or empty when absent or not a number
   */
  public OptionalDouble number(String key) {
    Object v = computes.get(key);
    return v instanceof Double ? OptionalDouble.of((Double) v) : OptionalDouble.empty();
  }

  /**
   * Returns a string compute result.
   *
   * @param key compute key
   * @return the text, or empty
   */
  public Optional<String> text(String key) {
    Object v = computes.get(key);
    return v instanceof String ? Optional.of((String) v) : Optional.empty();
  }

  /**
   * Returns a timeseries compute result.
   *
   * @param key compute key
   * @return the points, or an empty list
   */
  public List<DatadogTimeseriesPoint> timeseries(String key) {
    Object v = computes.get(key);
    if (!(v instanceof List)) {
      return List.of();
    }
    return ((List<?>) v)
        .stream()
            .filter(DatadogTimeseriesPoint.class::isInstance)
            .map(DatadogTimeseriesPoint.class::cast)
            .toList();
  }
}
