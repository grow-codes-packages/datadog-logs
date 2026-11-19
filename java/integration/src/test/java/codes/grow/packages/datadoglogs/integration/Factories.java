package codes.grow.packages.datadoglogs.integration;

import codes.grow.packages.datadoglogs.DatadogLogsClient;
import codes.grow.packages.datadoglogs.DatadogLogClient;
import codes.grow.packages.datadoglogs.core.EnvReader;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.logging.BatchOptions;
import codes.grow.packages.datadoglogs.testing.ConformanceRunner;
import codes.grow.packages.datadoglogs.testing.logging.LoggingTarget;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Conformance targets of this service against datadog/fakeintake.
 *
 * <ul>
 *   <li>{@code core}: a {@link DatadogLogsClient}. No {@code version} capability: Datadog is a SaaS
 *       and reports no server version ({@code serverVersion} raises UNSUPPORTED, asserted in
 *       {@link DatadogIntegrationTest}).
 *   <li>{@code logging}: capability {@code query}. The target uses the real {@code
 *       DatadogLogClient} (real intake requests) and a query client that reads the fakeintake
 *       capture back, so roundtrip / batch / redaction prove what actually reached the intake.
 *       Datadog's own search API runs only in the live sandbox job (unit-tested with fixtures,
 *       exercised against wire stubs in {@link DatadogIntegrationTest}).
 * </ul>
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"query"};

  private Factories() {}

  static DatadogLogsClient client(EnvReader env) {
    return DatadogLogsClient.builder()
        .fromEnv(env)
        .retry(RetryPolicy.builder().maxRetries(2).initialBackoff(Duration.ofMillis(50)).build())
        .build();
  }

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    Map<String, String> fakeEnv = new HashMap<>();
    env.string("URL").ifPresent(u -> fakeEnv.put("GROW_DATADOG_LOGS_URL", u));
    runner.target("core", scenario -> client(env));
    runner.target(
        "logging",
        scenario -> {
          DatadogLogsClient c = client(env);
          BatchOptions base = BatchOptions.builder().flushInterval(Duration.ofMillis(200)).build();
          DatadogLogClient logs =
              c.logClient(
                  DatadogLogClient.Options.builder()
                      .batchOptions(LoggingTarget.batchOptions(scenario, base))
                      .resource(Map.of("service.name", "sdk-java-conformance"))
                      .build());
          return LoggingTarget.of(logs, new FakeIntake(fakeEnv).query());
        });
  }
}
