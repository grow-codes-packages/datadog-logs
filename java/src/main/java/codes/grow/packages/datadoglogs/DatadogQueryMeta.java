package codes.grow.packages.datadoglogs;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Response metadata shared by search and aggregate ({@code meta}).
 *
 * @param nextCursor cursor of the next page ({@code meta.page.after}); empty on the last page
 * @param status {@code done}, or {@code timeout} for partial results
 * @param warnings non-fatal warnings (results may be partial)
 * @param requestId {@code meta.request_id}, else the response request-id header
 * @param elapsed server time ({@code meta.elapsed})
 */
public record DatadogQueryMeta(
    Optional<String> nextCursor,
    Optional<String> status,
    List<DatadogWarning> warnings,
    Optional<String> requestId,
    Optional<Duration> elapsed) {
  /**
   * Creates the metadata; null optionals become empty and the list is copied.
   *
   * @param nextCursor next cursor
   * @param status status
   * @param warnings warnings
   * @param requestId request id
   * @param elapsed server time
   */
  public DatadogQueryMeta {
    nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
    status = status == null ? Optional.empty() : status;
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    requestId = requestId == null ? Optional.empty() : requestId;
    elapsed = elapsed == null ? Optional.empty() : elapsed;
  }
}
