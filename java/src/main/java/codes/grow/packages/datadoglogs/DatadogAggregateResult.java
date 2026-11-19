package codes.grow.packages.datadoglogs;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of {@link DatadogLogsQuery#aggregateLogs}.
 *
 * @param buckets buckets
 * @param meta response metadata
 */
public record DatadogAggregateResult(List<DatadogBucket> buckets, DatadogQueryMeta meta) {
  /**
   * Creates the result; the list is copied.
   *
   * @param buckets buckets
   * @param meta metadata
   */
  public DatadogAggregateResult {
    buckets = List.copyOf(buckets);
    Objects.requireNonNull(meta, "meta");
  }

  /**
   * Returns the cursor of the next page of buckets.
   *
   * @return the cursor, or empty
   */
  public Optional<String> nextCursor() {
    return meta.nextCursor();
  }
}
