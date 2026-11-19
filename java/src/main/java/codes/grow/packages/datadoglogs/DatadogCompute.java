package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One computation of {@link DatadogLogsQuery#aggregateLogs}; results are keyed {@code c0}, {@code
 * c1}, … in order.
 *
 * <pre>{@code
 * DatadogCompute.of("count");
 * DatadogCompute.of("avg").withMetric("@duration");
 * DatadogCompute.of("count").timeseries("5m");
 * }</pre>
 *
 * @param aggregation {@code count}, {@code cardinality}, {@code pc75} … {@code pc99}, {@code sum},
 *     {@code min}, {@code max}, {@code avg} or {@code median}
 * @param type {@code total} (default) or {@code timeseries}
 * @param interval time bucket for {@code timeseries}, e.g. {@code 5m}
 * @param metric measure for non-count aggregations, e.g. {@code @duration}
 */
public record DatadogCompute(
    String aggregation, String type, Optional<String> interval, Optional<String> metric) {
  /**
   * Creates a compute.
   *
   * @param aggregation aggregation function (required)
   * @param type {@code total} or {@code timeseries}; null means {@code total}
   * @param interval bucket interval
   * @param metric measure
   * @throws ValidationException when the aggregation is blank
   */
  public DatadogCompute {
    if (aggregation == null || aggregation.isBlank()) {
      throw new ValidationException("logs.aggregateLogs: compute.aggregation is required");
    }
    type = type == null ? "total" : type;
    interval = interval == null ? Optional.empty() : interval;
    metric = metric == null ? Optional.empty() : metric;
  }

  /**
   * A {@code total} compute.
   *
   * @param aggregation aggregation function
   * @return the compute
   */
  public static DatadogCompute of(String aggregation) {
    return new DatadogCompute(aggregation, null, null, null);
  }

  /**
   * Returns a copy as a timeseries with the given bucket interval.
   *
   * @param interval bucket interval, e.g. {@code 5m}
   * @return the compute
   */
  public DatadogCompute timeseries(String interval) {
    return new DatadogCompute(aggregation, "timeseries", Optional.ofNullable(interval), metric);
  }

  /**
   * Returns a copy with a measure.
   *
   * @param metric measure, e.g. {@code @duration}
   * @return the compute
   */
  public DatadogCompute withMetric(String metric) {
    return new DatadogCompute(aggregation, type, interval, Optional.ofNullable(metric));
  }

  /**
   * Returns the wire form.
   *
   * @return a new map
   */
  public Map<String, Object> toWire() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("aggregation", aggregation);
    m.put("type", type);
    interval.ifPresent(i -> m.put("interval", i));
    metric.ifPresent(v -> m.put("metric", v));
    return m;
  }
}
