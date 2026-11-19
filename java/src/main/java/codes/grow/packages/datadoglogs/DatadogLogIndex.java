package codes.grow.packages.datadoglogs;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One Datadog log index ({@code GET /api/v1/logs/config/indexes}).
 *
 * @param name index name
 * @param query index filter query
 * @param retentionDays retention in days
 * @param dailyLimit daily event limit
 * @param rateLimited whether the index is currently rate limited
 */
public record DatadogLogIndex(
    String name,
    Optional<String> query,
    OptionalLong retentionDays,
    OptionalLong dailyLimit,
    Optional<Boolean> rateLimited) {
  /**
   * Creates the record; null optionals become empty.
   *
   * @param name index name
   * @param query filter query
   * @param retentionDays retention
   * @param dailyLimit daily limit
   * @param rateLimited rate limited flag
   */
  public DatadogLogIndex {
    Objects.requireNonNull(name, "name");
    query = query == null ? Optional.empty() : query;
    retentionDays = retentionDays == null ? OptionalLong.empty() : retentionDays;
    dailyLimit = dailyLimit == null ? OptionalLong.empty() : dailyLimit;
    rateLimited = rateLimited == null ? Optional.empty() : rateLimited;
  }
}
