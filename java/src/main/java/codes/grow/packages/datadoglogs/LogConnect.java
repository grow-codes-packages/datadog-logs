// Service-owned: generated once by sdk-kit, then maintained in this repository.
package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.logging.LogClient;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import codes.grow.packages.datadoglogs.logging.LoggingInit.LogConnection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Connector of {@code LoggingInit.initLogging()} for Datadog Log Management. */
public final class LogConnect {
  private LogConnect() {}

  /**
   * Builds the SDK transport: {@code DatadogLogsClient.fromEnv().logClient()}; null (stdout only)
   * when the client has no {@code logClient()}. Must not do network I/O. Services may replace it.
   *
   * @param resource resource attributes (service.name, deployment.environment)
   * @param env environment
   * @param onDropped re-emits records the SDK could not export (wire as {@code
   *     BatchOptions.onDrop}; the reflective default cannot, services wire it)
   * @return the connection or null
   */
  public static LogConnection connect(
      Map<String, Object> resource, Map<String, String> env, Consumer<List<LogRecord>> onDropped) {
    DatadogLogsClient client = DatadogLogsClient.fromEnv();
    try {
      Object logs = client.getClass().getMethod("logClient").invoke(client);
      LogClient lc = (LogClient) logs;
      return new LogConnection(
          lc,
          () -> {
            lc.close();
            client.close();
          });
    } catch (ReflectiveOperationException | ClassCastException e) {
      client.close();
      return null;
    }
  }
}
