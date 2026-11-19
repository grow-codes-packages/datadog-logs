package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.AuthenticationException;
import codes.grow.packages.datadoglogs.core.CancelledException;
import codes.grow.packages.datadoglogs.core.ConfigException;
import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.core.EnvReader;
import codes.grow.packages.datadoglogs.core.HealthCheck;
import codes.grow.packages.datadoglogs.core.HealthStatus;
import codes.grow.packages.datadoglogs.core.Hooks;
import codes.grow.packages.datadoglogs.core.HttpTransport;
import codes.grow.packages.datadoglogs.core.Json;
import codes.grow.packages.datadoglogs.core.Logger;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.core.Secret;
import codes.grow.packages.datadoglogs.core.TlsOptions;
import codes.grow.packages.datadoglogs.core.TransportRequest;
import codes.grow.packages.datadoglogs.core.TransportResponse;
import codes.grow.packages.datadoglogs.core.UnavailableException;
import codes.grow.packages.datadoglogs.core.UnsupportedException;
import codes.grow.packages.datadoglogs.core.ValidationException;
import codes.grow.packages.datadoglogs.logging.BatchOptions;
import codes.grow.packages.datadoglogs.logging.BatchingLogClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for Datadog Log Management: API key checks, the logs intake ({@link #logClient()}, {@link
 * #exporter()}) and the query API ({@link #query()}, needs the application key).
 *
 * <p>Implements SPEC section 2.4. Thread-safe; create one per Datadog site and close it when done.
 * Every I/O method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}.
 *
 * <pre>{@code
 * try (DatadogLogsClient client = DatadogLogsClient.fromEnv()) {
 *   DatadogLogClient logs = client.logClient();
 *   logs.logger("checkout").info("user signed in", Map.of("user", Map.of("id", 42)));
 * } // close() flushes the log client
 * }</pre>
 */
public final class DatadogLogsClient implements AutoCloseable {
  /** API key validation path. */
  public static final String VALIDATE_PATH = "/api/v1/validate";

  private final DatadogLogsConfig config;
  private final HttpTransport transport;
  private final DatadogHttp http;
  private final DatadogLogExporter exporter;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();
  private volatile DatadogLogsQuery query;

  private DatadogLogsClient(DatadogLogsConfig config) {
    this.config = config;
    this.transport = new HttpTransport(config.toTransportConfig());
    this.http = new DatadogHttp(transport, config);
    this.exporter = new DatadogLogExporter(http, DatadogLogExporter.Limits.defaults());
  }

  /**
   * Creates a client.
   *
   * @param config validated configuration
   * @return a new client
   * @throws ConfigException when the configuration, TLS or proxy settings are invalid
   */
  public static DatadogLogsClient create(DatadogLogsConfig config) {
    config.validate();
    return new DatadogLogsClient(config);
  }

  /**
   * Creates a client from {@code GROW_DATADOG_LOGS_*}, {@code DD_API_KEY}, {@code DD_SITE}, …
   *
   * @return a new client
   */
  public static DatadogLogsClient fromEnv() {
    return create(DatadogLogsConfig.fromEnv());
  }

  /**
   * Starts a client builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   */
  public DatadogLogsConfig config() {
    return config;
  }

  /**
   * Returns the HTTP transport (base URL = the API host).
   *
   * @return the transport
   */
  public HttpTransport transport() {
    return transport;
  }

  /**
   * Registers a resource (log client, tail) closed with the client.
   *
   * @param resource the resource
   * @throws CancelledException after {@link #close()}
   */
  public void register(AutoCloseable resource) {
    synchronized (resources) {
      if (closed.get()) {
        throw new CancelledException("client is closed");
      }
      resources.add(resource);
    }
  }

  private TransportRequest.Builder validateRequest(String operation) {
    return TransportRequest.get(DatadogHttp.join(config.apiUrl(), VALIDATE_PATH))
        .header(DatadogHttp.API_KEY_HEADER, config.apiKey().map(Secret::reveal).orElse(""))
        .operation(operation);
  }

  /** {@code valid} of a validate answer, or null when absent or not JSON. */
  private static Boolean valid(TransportResponse r) {
    try {
      JsonNode v = Json.tree(r.body()).get("valid");
      return v != null && v.isBoolean() ? v.asBoolean() : null;
    } catch (ValidationException e) {
      return null;
    }
  }

  /**
   * Reachability through {@code GET /api/v1/validate} (Datadog has no health endpoint): 2xx is
   * healthy {@code ok} ({@code details.apiKeyValid}); 401/403 is {@code unauthorized} (not
   * healthy); 429 {@code degraded}; other statuses {@code unhealthy}; network errors {@code
   * unreachable}. Never throws except for cancellation and configuration errors.
   *
   * @return the health status
   */
  public HealthStatus health() {
    return HttpTransport.await(healthAsync(), "health");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    return http.sendAsync(validateRequest("health").acceptAnyStatus().maxRetries(0))
        .handle(
            (resp, err) -> {
              if (err != null) {
                DatadogLogsException e = HttpTransport.asSdkException(err, "health");
                if (e instanceof CancelledException || e instanceof ConfigException) {
                  throw e;
                }
                return HealthCheck.fromError(e);
              }
              return healthFrom(resp);
            });
  }

  private static HealthStatus healthFrom(TransportResponse resp) {
    int s = resp.status();
    Map<String, String> details = new LinkedHashMap<>();
    details.put("httpStatus", Integer.toString(s));
    if (s >= 200 && s < 300) {
      details.put("apiKeyValid", Boolean.toString(Boolean.TRUE.equals(valid(resp))));
      return new HealthStatus(true, "ok", details);
    }
    if (s == 401 || s == 403) {
      details.put("apiKeyValid", "false");
      return new HealthStatus(false, "unauthorized", details);
    }
    return new HealthStatus(false, s == 429 ? "degraded" : "unhealthy", details);
  }

  /**
   * Validates the API key with {@code GET /api/v1/validate} and returns the latency.
   *
   * @return the latency
   * @throws AuthenticationException when Datadog rejects the key (401/403)
   * @throws UnavailableException when a 2xx answer has no {@code {"valid": true}}
   */
  public Duration ping() {
    return HttpTransport.await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return a future with the latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    return http.sendAsync(validateRequest("ping"))
        .thenApply(
            r -> {
              if (!Boolean.TRUE.equals(valid(r))) {
                throw unexpectedValidate("ping", r.status());
              }
              return Duration.ofNanos(System.nanoTime() - start);
            });
  }

  private static UnavailableException unexpectedValidate(String op, int status) {
    return new UnavailableException(
        op + ": unexpected response from /api/v1/validate (HTTP " + status + ")",
        status,
        null,
        null,
        null);
  }

  /**
   * Reports whether Datadog accepts the configured API key ({@code GET /api/v1/validate}): {@code
   * true} for {@code {"valid": true}}, {@code false} for HTTP 403 or {@code {"valid": false}}.
   * Never needs the application key.
   *
   * @return whether the key is valid
   * @throws UnavailableException when a 2xx answer has no {@code valid} field
   */
  public boolean validateApiKey() {
    return HttpTransport.await(validateApiKeyAsync(), "validateApiKey");
  }

  /**
   * Asynchronous {@link #validateApiKey()}.
   *
   * @return a future with the result
   */
  public CompletableFuture<Boolean> validateApiKeyAsync() {
    return http.sendAsync(validateRequest("validateApiKey").acceptStatus(403))
        .thenApply(
            r -> {
              if (r.status() == 403) {
                return false;
              }
              Boolean v = valid(r);
              if (v == null) {
                throw unexpectedValidate("validateApiKey", r.status());
              }
              return v;
            });
  }

  /**
   * Datadog is a SaaS without a version endpoint.
   *
   * @return never
   * @throws UnsupportedException always
   */
  public String serverVersion() {
    throw versionUnsupported();
  }

  /**
   * Asynchronous {@link #serverVersion()}: always failed with {@link UnsupportedException}.
   *
   * @return a failed future
   */
  public CompletableFuture<String> serverVersionAsync() {
    return CompletableFuture.failedFuture(versionUnsupported());
  }

  private static UnsupportedException versionUnsupported() {
    return new UnsupportedException("serverVersion: Datadog does not report a server version");
  }

  /**
   * Returns the intake exporter (Datadog limits). Most users call {@link #logClient()} instead.
   *
   * @return the exporter
   */
  public DatadogLogExporter exporter() {
    return exporter;
  }

  /**
   * A batching log client with the default options.
   *
   * @return a new log client
   */
  public DatadogLogClient logClient() {
    return logClient(DatadogLogClient.Options.defaults());
  }

  /**
   * A batching log client with the given batch options.
   *
   * @param batchOptions batch options (capped at 1000 records / 5 000 000 bytes per batch)
   * @return a new log client
   */
  public DatadogLogClient logClient(BatchOptions batchOptions) {
    return logClient(DatadogLogClient.Options.builder().batchOptions(batchOptions).build());
  }

  /**
   * A batching log client. The resource defaults to the configured {@code service} ({@code
   * service.name}), {@code version} ({@code service.version}), {@code env} ({@code
   * deployment.environment}) and {@code hostname} ({@code host.name}); {@code options.resource()}
   * wins. {@link #close()} flushes and closes every log client it created.
   *
   * @param options batching, resource, redaction and intake limits
   * @return a new log client
   * @throws CancelledException after {@link #close()}
   */
  public DatadogLogClient logClient(DatadogLogClient.Options options) {
    if (closed.get()) {
      throw new CancelledException("logClient: client is closed");
    }
    BatchOptions batch = options.batchOptions();
    if (batch.maxBatchSize() > DatadogLogMapping.MAX_ENTRIES_PER_REQUEST
        || batch.maxBatchBytes() > DatadogLogMapping.MAX_PAYLOAD_BYTES) {
      batch =
          batch.toBuilder()
              .maxBatchSize(
                  Math.min(batch.maxBatchSize(), DatadogLogMapping.MAX_ENTRIES_PER_REQUEST))
              .maxBatchBytes(Math.min(batch.maxBatchBytes(), DatadogLogMapping.MAX_PAYLOAD_BYTES))
              .build();
    }
    DatadogLogExporter exp =
        options.limits().equals(DatadogLogExporter.Limits.defaults())
            ? exporter
            : new DatadogLogExporter(http, options.limits());
    Map<String, Object> resource = new LinkedHashMap<>();
    config.service().ifPresent(v -> resource.put("service.name", v));
    config.version().ifPresent(v -> resource.put("service.version", v));
    config.env().ifPresent(v -> resource.put("deployment.environment", v));
    config.hostname().ifPresent(v -> resource.put("host.name", v));
    resource.putAll(options.resource());
    BatchingLogClient delegate =
        BatchingLogClient.builder(exp)
            .batchOptions(batch)
            .resource(resource)
            .redaction(options.redaction())
            .build();
    DatadogLogClient client = new DatadogLogClient(delegate, exp);
    try {
      register(client);
    } catch (CancelledException e) {
      client.close();
      throw e;
    }
    return client;
  }

  /**
   * The query client (search, aggregate, indexes, tail).
   *
   * @return the query client
   * @throws ConfigException without an application key
   */
  public DatadogLogsQuery query() {
    if (config.appKey().isEmpty()) {
      throw new ConfigException(
          "config: appKey is required for the query API (GROW_DATADOG_LOGS_APP_KEY or DD_APP_KEY)");
    }
    DatadogLogsQuery q = query;
    if (q == null) {
      q = new DatadogLogsQuery(this, http);
      query = q;
    }
    return q;
  }

  /**
   * Returns whether {@link #close()} was called.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Flushes and closes every log client, stops running tails, then releases connections.
   * Idempotent.
   */
  @Override
  public void close() {
    List<AutoCloseable> copy;
    synchronized (resources) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      copy = new ArrayList<>(resources);
      resources.clear();
    }
    for (AutoCloseable r : copy) {
      try {
        r.close();
      } catch (Exception ignored) {
        // best effort: close must not throw
      }
    }
    transport.close();
  }

  @Override
  public String toString() {
    return "DatadogLogsClient{" + config + "}";
  }

  /** Builder that configures and creates a client in one chain. */
  public static final class Builder {
    private final DatadogLogsConfig.Builder config = DatadogLogsConfig.builder();

    private Builder() {}

    /**
     * Fills unset fields from an environment reader.
     *
     * @param env reader, e.g. {@link EnvReader#system()}
     * @return this builder
     */
    public Builder fromEnv(EnvReader env) {
      config.fromEnv(env);
      return this;
    }

    /**
     * Sets the Datadog API key.
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      config.apiKey(apiKey);
      return this;
    }

    /**
     * Sets the Datadog API key.
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(Secret apiKey) {
      config.apiKey(apiKey);
      return this;
    }

    /**
     * Sets the Datadog application key (query API only).
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(String appKey) {
      config.appKey(appKey);
      return this;
    }

    /**
     * Sets the Datadog application key (query API only).
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(Secret appKey) {
      config.appKey(appKey);
      return this;
    }

    /**
     * Sets the Datadog site.
     *
     * @param site site such as {@code datadoghq.eu}
     * @return this builder
     */
    public Builder site(String site) {
      config.site(site);
      return this;
    }

    /**
     * Sets one base URL for the intake and the API.
     *
     * @param url URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Overrides the intake base URL.
     *
     * @param intakeUrl URL
     * @return this builder
     */
    public Builder intakeUrl(String intakeUrl) {
      config.intakeUrl(intakeUrl);
      return this;
    }

    /**
     * Overrides the API base URL.
     *
     * @param apiUrl URL
     * @return this builder
     */
    public Builder apiUrl(String apiUrl) {
      config.apiUrl(apiUrl);
      return this;
    }

    /**
     * Sets the default service.
     *
     * @param service service
     * @return this builder
     */
    public Builder service(String service) {
      config.service(service);
      return this;
    }

    /**
     * Sets the environment ({@code env:} tag).
     *
     * @param env environment
     * @return this builder
     */
    public Builder env(String env) {
      config.env(env);
      return this;
    }

    /**
     * Sets the version ({@code version:} tag).
     *
     * @param version version
     * @return this builder
     */
    public Builder version(String version) {
      config.version(version);
      return this;
    }

    /**
     * Sets the tags added to every log.
     *
     * @param tags {@code key:value} strings
     * @return this builder
     */
    public Builder tags(List<String> tags) {
      config.tags(tags);
      return this;
    }

    /**
     * Sets the default hostname.
     *
     * @param hostname host name
     * @return this builder
     */
    public Builder hostname(String hostname) {
      config.hostname(hostname);
      return this;
    }

    /**
     * Sets the {@code ddsource}.
     *
     * @param source source
     * @return this builder
     */
    public Builder source(String source) {
      config.source(source);
      return this;
    }

    /**
     * Sets the connect timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      config.connectTimeout(d);
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      config.requestTimeout(d);
      return this;
    }

    /**
     * Sets the total timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      config.totalTimeout(d);
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      config.retry(retry);
      return this;
    }

    /**
     * Sets the TLS options.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      config.tls(tls);
      return this;
    }

    /**
     * Sets the proxy URL.
     *
     * @param proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      config.proxy(proxy);
      return this;
    }

    /**
     * Adds a header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      config.header(name, value);
      return this;
    }

    /**
     * Sets the user agent suffix.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      config.userAgentSuffix(suffix);
      return this;
    }

    /**
     * Sets hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      config.hooks(hooks);
      return this;
    }

    /**
     * Sets the logger.
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      config.logger(logger);
      return this;
    }

    /**
     * Builds the configuration and the client.
     *
     * @return a new client
     * @throws ConfigException when the configuration is invalid
     */
    public DatadogLogsClient build() {
      return create(config.build());
    }
  }
}
