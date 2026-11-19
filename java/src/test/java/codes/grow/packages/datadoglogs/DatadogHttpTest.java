package codes.grow.packages.datadoglogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadoglogs.core.AuthenticationException;
import codes.grow.packages.datadoglogs.core.Hooks;
import codes.grow.packages.datadoglogs.core.NotFoundException;
import codes.grow.packages.datadoglogs.core.PermissionDeniedException;
import codes.grow.packages.datadoglogs.core.RateLimitException;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.core.TransportRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogHttpTest {
  private static final String PATH = "/api/v1/validate";

  private DdServer server;

  @BeforeEach
  void start() {
    server = new DdServer();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private DatadogLogsClient client(RetryPolicy retry, Duration total, Hooks hooks) {
    return DatadogLogsClient.builder()
        .url(server.url())
        .apiKey(DatadogLogsClientTest.API_KEY)
        .retry(retry)
        .totalTimeout(total)
        .hooks(hooks)
        .build();
  }

  private static DatadogHttp http(DatadogLogsClient c) {
    return new DatadogHttp(c.transport(), c.config());
  }

  private static TransportRequest.Builder get() {
    return TransportRequest.get(PATH).operation("probe");
  }

  @Test
  void rateLimitResetIsTheRetryHint() {
    List<Hooks.ErrorInfo> errors = new CopyOnWriteArrayList<>();
    Hooks hooks =
        new Hooks() {
          @Override
          public void onError(ErrorInfo info) {
            errors.add(info);
            throw new IllegalStateException("hooks must not break calls");
          }
        };
    server
        .on(PATH, 429, "{\"errors\":[\"Too many requests\"]}", "X-RateLimit-Reset", "0")
        .on(PATH, 429, "{}", "Retry-After", "0")
        .on(PATH, 200, "{\"valid\":true}");
    try (var c = client(DatadogLogsClientTest.fastRetry(), Duration.ofSeconds(10), hooks)) {
      DatadogHttp h = http(c);
      assertThat(h.transport()).isSameAs(c.transport());
      assertThat(h.send(get()).status()).isEqualTo(200);
      assertThat(server.requests(PATH)).hasSize(3);
      assertThat(errors).hasSize(2);
      assertThat(errors.get(0).willRetry()).isTrue();
      assertThat(errors.get(0).error()).isInstanceOf(RateLimitException.class);
      assertThat(errors.get(0).error().retryAfter()).contains(Duration.ZERO);
      assertThat(errors.get(0).url()).isEqualTo(server.url() + PATH);
    }
  }

  @Test
  void rateLimitHintTooLongFailsAtOnce() {
    server.on(PATH, 429, "{\"errors\":[\"Too many requests\"]}", "X-RateLimit-Reset", "3600");
    try (var c = client(DatadogLogsClientTest.fastRetry(), Duration.ofSeconds(10), Hooks.NONE)) {
      assertThatThrownBy(() -> http(c).send(get()))
          .isInstanceOfSatisfying(
              RateLimitException.class,
              e -> {
                assertThat(e.retryAfter()).contains(Duration.ofHours(1));
                assertThat(e.httpStatus()).hasValue(429);
                assertThat(e.getMessage()).contains("Too many requests");
              });
      assertThat(server.requests(PATH)).hasSize(1);
    }
  }

  @Test
  void rateLimitRetriesExhaustedAndTotalBudget() {
    server.always(PATH, 429, "{}");
    RetryPolicy one =
        RetryPolicy.builder().maxRetries(1).initialBackoff(Duration.ofMillis(1)).build();
    try (var c = client(one, Duration.ofSeconds(10), Hooks.NONE)) {
      assertThatThrownBy(() -> http(c).send(get())).isInstanceOf(RateLimitException.class);
      assertThat(server.requests(PATH)).hasSize(2);
    }
    server.close();
    server = new DdServer();
    server.always(PATH, 429, "{}", "X-RateLimit-Reset", "5");
    try (var c = client(one, Duration.ofMillis(500), Hooks.NONE)) {
      assertThatThrownBy(() -> http(c).send(get())).isInstanceOf(RateLimitException.class);
      assertThat(server.requests(PATH)).hasSize(1);
    }
  }

  @Test
  void callerAccepting429GetsTheResponse() {
    server.on(PATH, 429, "{}");
    try (var c = client(DatadogLogsClientTest.fastRetry(), Duration.ofSeconds(10), Hooks.NONE)) {
      assertThat(http(c).send(get().acceptStatus(429)).status()).isEqualTo(429);
    }
  }

  @Test
  void forbiddenBecomesAuthentication() {
    server.fixture(PATH, "searchLogs.403_invalid_keys").on(PATH, 404, "{}");
    try (var c = client(DatadogLogsClientTest.fastRetry(), Duration.ofSeconds(10), Hooks.NONE)) {
      assertThatThrownBy(() -> http(c).send(get()))
          .isInstanceOfSatisfying(
              AuthenticationException.class, e -> assertThat(e.httpStatus()).hasValue(403));
      assertThatThrownBy(() -> http(c).send(get())).isInstanceOf(NotFoundException.class);
    }
    PermissionDeniedException noStatus = new PermissionDeniedException("x");
    assertThat(DatadogHttp.map(noStatus)).isSameAs(noStatus);
    PermissionDeniedException other = new PermissionDeniedException("x", 401, null, null, null);
    assertThat(DatadogHttp.map(other)).isSameAs(other);
  }

  @Test
  void resetHintParsing() {
    assertThat(DatadogHttp.resetHint(null)).isEmpty();
    assertThat(DatadogHttp.resetHint("soon")).isEmpty();
    assertThat(DatadogHttp.resetHint(" 1.5 ")).contains(Duration.ofMillis(1500));
    assertThat(DatadogHttp.join("http://a//", "/p")).isEqualTo("http://a/p");
  }

  @Test
  void cancellationStopsInflightAndPendingRetries() throws Exception {
    server.onDelayed(PATH, 2000, 200, "{}");
    try (var c = client(DatadogLogsClientTest.fastRetry(), Duration.ofSeconds(10), Hooks.NONE)) {
      CompletableFuture<?> f = http(c).sendAsync(get());
      Thread.sleep(100);
      assertThat(f.cancel(true)).isTrue();
    }
    server.close();
    server = new DdServer();
    server.on(PATH, 429, "{}", "Retry-After", "1").always(PATH, 200, "{}");
    try (var c = client(DatadogLogsClientTest.fastRetry(), Duration.ofSeconds(10), Hooks.NONE)) {
      CompletableFuture<?> f = http(c).sendAsync(get());
      Thread.sleep(300);
      f.cancel(true);
      Thread.sleep(1200);
      assertThat(server.requests(PATH)).hasSize(1);
    }
  }
}
