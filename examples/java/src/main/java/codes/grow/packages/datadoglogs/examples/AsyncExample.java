// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.datadoglogs.examples;

import codes.grow.packages.datadoglogs.DatadogLogsClient;
import codes.grow.packages.datadoglogs.core.HealthStatus;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** The {@code …Async} methods: run calls concurrently and cancel a slow one. */
public final class AsyncExample {
  private AsyncExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    try (var client = DatadogLogsClient.fromEnv()) {
      CompletableFuture<HealthStatus> health = client.healthAsync();
      CompletableFuture<Duration> ping = client.pingAsync();
      CompletableFuture.allOf(health, ping.exceptionally(e -> null)).join();
      System.out.println("health: " + health.join());
      ping.whenComplete(
          (latency, error) ->
              System.out.println(error == null ? "ping: " + latency : "ping failed: " + error));

      // Cancelling a future aborts the HTTP exchange and any pending retry.
      CompletableFuture<Boolean> valid = client.validateApiKeyAsync();
      valid.cancel(true);
      System.out.println("validateApiKey cancelled: " + valid.isCancelled());

      // The log client flushes in the background; flushAsync waits without blocking a thread.
      var logs = client.logClient();
      logs.logger("async-example").info("hello from AsyncExample");
      logs.flushAsync(Duration.ofSeconds(10))
          .thenRun(() -> System.out.println("flushed: " + logs.stats()))
          .join();
    }
  }
}
