// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.datadoglogs.examples;

import codes.grow.packages.datadoglogs.AggregateLogsOptions;
import codes.grow.packages.datadoglogs.DatadogCompute;
import codes.grow.packages.datadoglogs.DatadogGroupBy;
import codes.grow.packages.datadoglogs.DatadogLogClient;
import codes.grow.packages.datadoglogs.DatadogLogEvent;
import codes.grow.packages.datadoglogs.DatadogLogsClient;
import codes.grow.packages.datadoglogs.DatadogLogsPage;
import codes.grow.packages.datadoglogs.DatadogLogsQuery;
import codes.grow.packages.datadoglogs.SearchLogsRequest;
import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.Logger;
import codes.grow.packages.datadoglogs.logging.Severity;
import codes.grow.packages.datadoglogs.logging.WaitForLogOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Datadog Log Management with the Java SDK: configuration from the environment, API key check,
 * shipping logs through the batching log client (Logger facade + direct records), and — when an
 * application key is set — reading them back with the search, aggregate and index APIs.
 *
 * <pre>
 * DD_API_KEY=... DD_SITE=datadoghq.eu [DD_APP_KEY=...] mvn -q compile exec:java
 * # against the Docker fakeintake: GROW_DATADOG_LOGS_URL=http://127.0.0.1:PORT DD_API_KEY=test
 * </pre>
 */
public final class BasicExample {
  private BasicExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    String marker = "example-" + Long.toString(System.currentTimeMillis(), 36);
    try (DatadogLogsClient client = DatadogLogsClient.fromEnv()) {
      System.out.println("config: " + client.config()); // keys print as [REDACTED]
      System.out.println("health: " + client.health()); // never throws for an unreachable service
      System.out.println("API key valid: " + client.validateApiKey());

      // 1. Ship logs: a batching LogClient (gzip, <= 1000 entries / 5 MB per request).
      DatadogLogClient logs =
          client.logClient(
              DatadogLogClient.Options.builder()
                  .resource(Map.of("deployment.environment", "example"))
                  .build());
      Logger log = logs.logger("example", Map.of("marker", marker));
      log.info(
          "hello from the Grow Codes SDK " + marker,
          Map.of("user", Map.of("id", 42), "password", "never-sent"));
      log.child(Map.of("step", "checkout")).warn("payment slow " + marker, Map.of("durationMs", 1234));
      logs.send(
          LogRecord.builder()
              .body("traced " + marker)
              .severity(Severity.ERROR)
              .traceId("4bf92f3577b34da6a3ce929d0e0e4736") // sent as dd.trace_id (decimal)
              .spanId("00f067aa0ba902b7")
              .build());
      logs.flush();
      System.out.println("sent: " + logs.stats());

      // 2. Read back (needs DD_APP_KEY with logs_read_data; Datadog indexes within seconds).
      if (client.config().appKey().isEmpty()) {
        System.out.println("set DD_APP_KEY to read the logs back");
        return;
      }
      DatadogLogsQuery query = client.query();
      LogRecord found =
          query.waitForLog(
              "\"" + marker + "\"",
              WaitForLogOptions.of(Duration.ofMinutes(2), Duration.ofSeconds(5)));
      System.out.println("found: " + found.body() + " " + found.severityText());

      DatadogLogsPage page =
          query.searchLogs(
              SearchLogsRequest.builder()
                  .query("\"" + marker + "\"")
                  .from("now-15m")
                  .limit(10)
                  .build());
      System.out.println("search: " + page.events().size() + " events, next: " + page.nextCursor());
      for (DatadogLogEvent e :
          query.iterateLogs(
              SearchLogsRequest.builder().query("\"" + marker + "\"").limit(100).build())) {
        System.out.println(" - " + e.timestamp().orElse(null) + " " + e.status() + " " + e.message());
      }
      System.out.println(
          "count by status: "
              + query
                  .aggregateLogs(
                      List.of(DatadogCompute.of("count")),
                      List.of(DatadogGroupBy.of("status")),
                      AggregateLogsOptions.builder().query("\"" + marker + "\"").build())
                  .buckets());
      try {
        System.out.println("indexes: " + query.listStreams()); // needs logs_read_config
      } catch (DatadogLogsException e) {
        System.out.println("indexes: not readable with this key: " + e.getMessage());
      }
    } catch (DatadogLogsException e) {
      // Canonical code + HTTP status; the message never contains keys.
      System.err.println(e.code() + ": " + e.getMessage() + " (HTTP " + e.httpStatus() + ")");
      System.exit(1);
    }
  }
}
