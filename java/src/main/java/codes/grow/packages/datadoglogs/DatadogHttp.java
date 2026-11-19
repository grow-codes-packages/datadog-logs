package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.AuthenticationException;
import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.core.Hooks;
import codes.grow.packages.datadoglogs.core.HttpTransport;
import codes.grow.packages.datadoglogs.core.PermissionDeniedException;
import codes.grow.packages.datadoglogs.core.RateLimitException;
import codes.grow.packages.datadoglogs.core.Redaction;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.core.TransportRequest;
import codes.grow.packages.datadoglogs.core.TransportResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Datadog-specific plumbing on top of the generated transport.
 *
 * <ul>
 *   <li>DECISIONS 3: a 403 (missing or invalid key, or a key without the needed permission, which
 *       Datadog does not tell apart) becomes {@link AuthenticationException}, keeping status 403.
 *   <li>DECISIONS 5: a 429 without {@code Retry-After} uses {@code X-RateLimit-Reset} (seconds
 *       until the window resets) as the retry hint. The transport only reads {@code Retry-After},
 *       so 429 answers are retried here with the same policy (full jitter, {@code maxRetries},
 *       {@code maxRetryAfter}, total timeout).
 * </ul>
 */
final class DatadogHttp {
  /** API key header. */
  static final String API_KEY_HEADER = "DD-API-KEY";

  /** Application key header (query API only). */
  static final String APP_KEY_HEADER = "DD-APPLICATION-KEY";

  /** Rate-limit reset header (seconds until the window resets). */
  static final String RATE_LIMIT_RESET_HEADER = "x-ratelimit-reset";

  private final HttpTransport transport;
  private final DatadogLogsConfig config;

  DatadogHttp(HttpTransport transport, DatadogLogsConfig config) {
    this.transport = transport;
    this.config = config;
  }

  HttpTransport transport() {
    return transport;
  }

  DatadogLogsConfig config() {
    return config;
  }

  /**
   * Joins a base URL and an absolute path.
   *
   * @param base base URL
   * @param path path starting with a slash
   * @return the URL
   */
  static String join(String base, String path) {
    String b = base;
    while (b.endsWith("/")) {
      b = b.substring(0, b.length() - 1);
    }
    return b + path;
  }

  /**
   * Sends a request (blocking), see {@link #sendAsync}.
   *
   * @param builder request builder
   * @return the response
   */
  TransportResponse send(TransportRequest.Builder builder) {
    TransportRequest probe = builder.build();
    return HttpTransport.await(sendAsync(builder), probe.operation());
  }

  /**
   * Sends a request with the Datadog error mapping and the 429 retry hint.
   *
   * @param builder request builder; 429 is accepted on it unless the caller already accepts it
   * @return the response future, failed with a mapped SDK exception
   */
  CompletableFuture<TransportResponse> sendAsync(TransportRequest.Builder builder) {
    TransportRequest probe = builder.build();
    boolean callerAccepts429 = probe.accepts(429);
    TransportRequest request = callerAccepts429 ? probe : builder.acceptStatus(429).build();
    CompletableFuture<TransportResponse> result = new CompletableFuture<>();
    AtomicReference<CompletableFuture<?>> current = new AtomicReference<>();
    result.whenComplete(
        (r, t) -> {
          CompletableFuture<?> c = current.get();
          if (result.isCancelled() && c != null) {
            c.cancel(true);
          }
        });
    long deadline = System.nanoTime() + config.totalTimeout().toNanos();
    attempt(request, !callerAccepts429, 1, deadline, result, current);
    return result;
  }

  private void attempt(
      TransportRequest request,
      boolean handle429,
      int attemptNo,
      long deadline,
      CompletableFuture<TransportResponse> result,
      AtomicReference<CompletableFuture<?>> current) {
    if (result.isDone()) {
      return;
    }
    CompletableFuture<TransportResponse> f = transport.sendAsync(request);
    current.set(f);
    f.whenComplete(
        (resp, err) -> {
          if (err != null) {
            result.completeExceptionally(
                map(HttpTransport.asSdkException(err, request.operation())));
            return;
          }
          if (!handle429 || resp.status() != 429) {
            result.complete(resp);
            return;
          }
          RateLimitException error = rateLimited(request, resp);
          RetryPolicy policy = config.retry();
          int maxRetries = request.maxRetries().orElse(policy.maxRetries());
          Duration hint = error.retryAfter().orElse(null);
          Duration wait = policy.backoff(attemptNo - 1);
          if (hint != null && hint.compareTo(wait) > 0) {
            wait = hint;
          }
          boolean retry =
              attemptNo <= maxRetries
                  && (hint == null || hint.compareTo(policy.maxRetryAfter()) <= 0)
                  && wait.toNanos() < deadline - System.nanoTime();
          notifyError(request, error, attemptNo, retry);
          if (!retry) {
            result.completeExceptionally(error);
            return;
          }
          CompletableFuture.delayedExecutor(wait.toNanos(), TimeUnit.NANOSECONDS)
              .execute(() -> attempt(request, true, attemptNo + 1, deadline, result, current));
        });
  }

  private RateLimitException rateLimited(TransportRequest request, TransportResponse resp) {
    String message =
        HttpTransport.errorMessage(
            request.operation(), 429, resp.headers(), resp.body(), config.knownSecrets());
    Instant now = Instant.now();
    Optional<Duration> hint =
        RetryPolicy.parseRetryAfter(resp.header("retry-after").orElse(null), now);
    if (hint.isEmpty()) {
      hint = resetHint(resp.header(RATE_LIMIT_RESET_HEADER).orElse(null));
    }
    return new RateLimitException(
        message, 429, resp.requestId().orElse(null), hint.orElse(null), null);
  }

  /**
   * Parses {@code X-RateLimit-Reset}: seconds until the window resets.
   *
   * @param value header value, may be null
   * @return the wait, or empty
   */
  static Optional<Duration> resetHint(String value) {
    if (value == null || !value.trim().matches("\\d+(\\.\\d+)?")) {
      return Optional.empty();
    }
    return Optional.of(Duration.ofMillis((long) (Double.parseDouble(value.trim()) * 1000)));
  }

  private void notifyError(
      TransportRequest request, DatadogLogsException error, int attemptNo, boolean retry) {
    try {
      String url = Redaction.redactUrl(transport.resolve(request.path(), request.query()));
      config.hooks().onError(new Hooks.ErrorInfo(request.method(), url, error, attemptNo, retry));
    } catch (RuntimeException ignored) {
      // hooks must never break a call (SPEC section 2.5)
    }
  }

  /**
   * Applies the Datadog error mapping: a 403 becomes {@link AuthenticationException}.
   *
   * @param e SDK exception
   * @return the mapped exception
   */
  static DatadogLogsException map(DatadogLogsException e) {
    if (e instanceof PermissionDeniedException
        && e.httpStatus().isPresent()
        && e.httpStatus().getAsInt() == 403) {
      return new AuthenticationException(
          e.getMessage(),
          403,
          e.requestId().orElse(null),
          e.retryAfter().orElse(null),
          e.getCause());
    }
    return e;
  }
}
