package codes.grow.packages.datadoglogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadoglogs.core.AuthenticationException;
import codes.grow.packages.datadoglogs.core.CancelledException;
import codes.grow.packages.datadoglogs.core.ConfigException;
import codes.grow.packages.datadoglogs.core.EnvReader;
import codes.grow.packages.datadoglogs.core.HealthStatus;
import codes.grow.packages.datadoglogs.core.Hooks;
import codes.grow.packages.datadoglogs.core.Logger;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.core.SdkInfo;
import codes.grow.packages.datadoglogs.core.Secret;
import codes.grow.packages.datadoglogs.core.TlsOptions;
import codes.grow.packages.datadoglogs.core.UnavailableException;
import codes.grow.packages.datadoglogs.core.UnsupportedException;
import codes.grow.packages.datadoglogs.logging.BatchOptions;
import codes.grow.packages.datadoglogs.logging.LogInput;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogLogsClientTest {
  static final String API_KEY = "test-api-key-0001";
  static final String APP_KEY = "test-app-key-0002";
  private static final String V = DatadogLogsClient.VALIDATE_PATH;

  private DdServer server;

  @BeforeEach
  void start() {
    server = new DdServer();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  static RetryPolicy fastRetry() {
    return RetryPolicy.builder()
        .initialBackoff(Duration.ofMillis(1))
        .maxBackoff(Duration.ofMillis(5))
        .build();
  }

  static DatadogLogsClient client(DdServer s) {
    return DatadogLogsClient.builder()
        .url(s.url())
        .apiKey(API_KEY)
        .appKey(APP_KEY)
        .retry(fastRetry())
        .build();
  }

  @Test
  void healthVariants() {
    server
        .fixture(V, "validateApiKey.ok")
        .on(V, 200, "{}")
        .fixture(V, "validateApiKey.403_invalid_key")
        .on(V, 401, "{\"errors\":[\"Unauthorized\"]}")
        .on(V, 429, "{}")
        .on(V, 500, "oops")
        .on(V, 200, "not json");
    try (var c = client(server)) {
      HealthStatus ok = c.health();
      assertThat(ok.healthy()).isTrue();
      assertThat(ok.status()).isEqualTo("ok");
      assertThat(ok.details())
          .containsEntry("apiKeyValid", "true")
          .containsEntry("httpStatus", "200");
      assertThat(c.health().details()).containsEntry("apiKeyValid", "false");
      HealthStatus forbidden = c.health();
      assertThat(forbidden.healthy()).isFalse();
      assertThat(forbidden.status()).isEqualTo("unauthorized");
      assertThat(forbidden.details()).containsEntry("httpStatus", "403");
      assertThat(c.health().status()).isEqualTo("unauthorized");
      assertThat(c.health().status()).isEqualTo("degraded");
      assertThat(c.healthAsync().join().status()).isEqualTo("unhealthy");
      assertThat(c.health().details()).containsEntry("apiKeyValid", "false");
      List<DdServer.Req> reqs = server.requests(V);
      assertThat(reqs).hasSize(7);
      assertThat(reqs.get(0).header("DD-API-KEY")).isEqualTo(API_KEY);
      assertThat(reqs.get(0).header("DD-APPLICATION-KEY")).isNull();
      assertThat(reqs.get(0).header("User-Agent")).isEqualTo(SdkInfo.userAgent());
    }
  }

  @Test
  void core_health_unreachable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    try (var c =
        DatadogLogsClient.builder().url("http://127.0.0.1:" + port).apiKey(API_KEY).build()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isFalse();
      assertThat(h.status()).isEqualTo("unreachable");
      assertThat(c.healthAsync().join().status()).isEqualTo("unreachable");
    }
  }

  @Test
  void healthThrowsOnlyForCancel() {
    DatadogLogsClient c = client(server);
    c.close();
    assertThatThrownBy(c::health).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> c.healthAsync().join()).hasCauseInstanceOf(CancelledException.class);
  }

  @Test
  void ping() {
    server
        .fixture(V, "validateApiKey.datadog_ok")
        .fixture(V, "validateApiKey.403_invalid_key")
        .on(V, 200, "{}")
        .on(V, 200, "{\"valid\":true}");
    try (var c = client(server)) {
      assertThat(c.ping()).isPositive();
      assertThatThrownBy(c::ping)
          .isInstanceOf(AuthenticationException.class)
          .satisfies(e -> assertThat(((AuthenticationException) e).httpStatus()).hasValue(403))
          .message()
          .doesNotContain(API_KEY);
      assertThatThrownBy(c::ping)
          .isInstanceOf(UnavailableException.class)
          .hasMessageContaining("unexpected response");
      assertThat(c.pingAsync().join()).isPositive();
    }
  }

  @Test
  void validateApiKey() {
    server
        .fixture(V, "validateApiKey.ok")
        .fixture(V, "validateApiKey.403_missing_key")
        .on(V, 200, "{\"valid\":false}")
        .on(V, 200, "")
        .on(V, 401, "{\"errors\":[\"Unauthorized\"]}");
    try (var c = client(server)) {
      assertThat(c.validateApiKey()).isTrue();
      assertThat(c.validateApiKeyAsync().join()).isFalse();
      assertThat(c.validateApiKey()).isFalse();
      assertThatThrownBy(c::validateApiKey).isInstanceOf(UnavailableException.class);
      assertThatThrownBy(c::validateApiKey).isInstanceOf(AuthenticationException.class);
      assertThat(server.requests(V).get(0).header("DD-APPLICATION-KEY")).isNull();
    }
  }

  @Test
  void serverVersionIsUnsupported() {
    try (var c = client(server)) {
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(() -> c.serverVersionAsync().join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(UnsupportedException.class);
      assertThat(server.requests()).isEmpty();
    }
  }

  @Test
  void core_close_idempotent() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    DatadogLogsClient c = client(server);
    c.register(closed::incrementAndGet);
    c.register(
        () -> {
          throw new IllegalStateException("ignored");
        });
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(closed).hasValue(1);
    assertThat(c.transport().isClosed()).isTrue();
    assertThatThrownBy(c::ping).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> c.register(closed::incrementAndGet))
        .isInstanceOf(CancelledException.class);
    assertThatThrownBy(c::logClient).isInstanceOf(CancelledException.class);
  }

  @Test
  void closeFlushesLogClients() {
    server.always("/api/v2/logs", 202, "{}");
    DatadogLogsClient c = client(server);
    DatadogLogClient logs =
        c.logClient(BatchOptions.builder().flushInterval(Duration.ofHours(1)).build());
    logs.send(LogInput.of("closing"));
    c.close();
    assertThat(server.requests("/api/v2/logs")).hasSize(1);
    assertThat(server.requests("/api/v2/logs").get(0).body()).contains("\"message\":\"closing\"");
  }

  @Test
  void logClientResourceAndCaps() {
    try (var c =
        DatadogLogsClient.builder()
            .url(server.url())
            .apiKey(API_KEY)
            .service("svc")
            .version("1.0")
            .env("prod")
            .hostname("h1")
            .build()) {
      DatadogLogClient lc =
          c.logClient(
              DatadogLogClient.Options.builder()
                  .batchOptions(
                      BatchOptions.builder()
                          .maxQueueSize(5000)
                          .maxBatchSize(2000)
                          .maxBatchBytes(10_000_000)
                          .build())
                  .resource(Map.of("service.name", "override"))
                  .redaction(null)
                  .limits(new DatadogLogExporter.Limits(10, 1000, 500))
                  .build());
      assertThat(lc.resource())
          .containsEntry("service.name", "override")
          .containsEntry("service.version", "1.0")
          .containsEntry("deployment.environment", "prod")
          .containsEntry("host.name", "h1");
      assertThat(lc.processor().options().maxBatchSize()).isEqualTo(1000);
      assertThat(lc.processor().options().maxBatchBytes()).isEqualTo(5_000_000);
      assertThat(lc.exporter()).isNotSameAs(c.exporter());
      assertThat(lc.exporter().limits().maxEntriesPerRequest()).isEqualTo(10);
      DatadogLogClient small = c.logClient(BatchOptions.builder().maxBatchSize(10).build());
      assertThat(small.processor().options().maxBatchSize()).isEqualTo(10);
      assertThat(small.exporter()).isSameAs(c.exporter());
      DatadogLogClient onlyBytes =
          c.logClient(BatchOptions.builder().maxBatchSize(10).maxBatchBytes(6_000_000).build());
      assertThat(onlyBytes.processor().options().maxBatchBytes()).isEqualTo(5_000_000);
      assertThat(DatadogLogClient.Options.defaults().redaction()).isNotNull();
      assertThat(DatadogLogClient.Options.defaults().resource()).isEmpty();
    }
  }

  @Test
  void queryNeedsAppKey() {
    try (var c = DatadogLogsClient.builder().url(server.url()).apiKey(API_KEY).build()) {
      assertThatThrownBy(c::query)
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining("appKey");
    }
    try (var c = client(server)) {
      assertThat(c.query()).isSameAs(c.query());
    }
  }

  @Test
  void builderSetsEveryField() {
    Hooks hooks = new Hooks() {};
    Logger logger = new Logger() {};
    try (var c =
        DatadogLogsClient.builder()
            .apiKey(Secret.of("s3cr3t-value"))
            .appKey(Secret.of("app-s3cr3t"))
            .site("datadoghq.eu")
            .url("http://u")
            .intakeUrl("http://i")
            .apiUrl("http://a")
            .service("s")
            .env("e")
            .version("v")
            .tags(List.of("t:1"))
            .hostname("h")
            .source("src")
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(2))
            .totalTimeout(Duration.ofSeconds(3))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://proxy.invalid:3128")
            .header("X-A", "1")
            .userAgentSuffix("app/1")
            .hooks(hooks)
            .logger(logger)
            .build()) {
      DatadogLogsConfig cfg = c.config();
      assertThat(cfg.site()).isEqualTo("datadoghq.eu");
      assertThat(cfg.intakeUrl()).isEqualTo("http://i");
      assertThat(cfg.apiUrl()).isEqualTo("http://a");
      assertThat(cfg.url()).contains("http://u");
      assertThat(cfg.service()).contains("s");
      assertThat(cfg.env()).contains("e");
      assertThat(cfg.version()).contains("v");
      assertThat(cfg.tags()).containsExactly("t:1");
      assertThat(cfg.hostname()).contains("h");
      assertThat(cfg.source()).isEqualTo("src");
      assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
      assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
      assertThat(cfg.totalTimeout()).isEqualTo(Duration.ofSeconds(3));
      assertThat(cfg.retry().maxRetries()).isZero();
      assertThat(cfg.tls().isCustom()).isFalse();
      assertThat(cfg.proxy()).contains("http://proxy.invalid:3128");
      assertThat(cfg.headers()).containsEntry("X-A", "1");
      assertThat(cfg.userAgentSuffix()).contains("app/1");
      assertThat(cfg.hooks()).isSameAs(hooks);
      assertThat(cfg.logger()).isSameAs(logger);
      assertThat(c.toString()).doesNotContain("s3cr3t-value").doesNotContain("app-s3cr3t");
      assertThat(c.transport().config().knownSecrets())
          .containsExactly("s3cr3t-value", "app-s3cr3t");
    }
  }

  @Test
  void createAndFromEnv() {
    try (var c =
        DatadogLogsClient.create(
            DatadogLogsConfig.builder().url(server.url()).apiKey(API_KEY).build())) {
      assertThat(c.isClosed()).isFalse();
    }
    try (var c = DatadogLogsClient.fromEnv()) {
      assertThat(c.config()).isNotNull();
    } catch (ConfigException e) {
      assertThat(e.getMessage()).contains("apiKey");
    }
    EnvReader env =
        EnvReader.of(Map.of(EnvReader.DEFAULT_PREFIX + "URL", server.url(), "DD_API_KEY", API_KEY));
    try (var c = DatadogLogsClient.builder().fromEnv(env).build()) {
      assertThat(c.config().apiUrl()).isEqualTo(server.url());
    }
  }

  @Test
  void logClientDelegates() {
    server.always("/api/v2/logs", 202, "{}");
    try (var c = client(server)) {
      DatadogLogClient logs = c.logClient();
      logs.sendBatch(List.of(LogInput.of("a"), LogInput.of("b")));
      logs.flush(Duration.ofSeconds(5));
      logs.send(LogInput.of("c"));
      logs.flushAsync(Duration.ofSeconds(5)).join();
      logs.logger("svc").info("d");
      logs.flush();
      assertThat(logs.stats().sent()).isEqualTo(4);
      logs.closeAsync().join();
      logs.close();
      assertThat(server.requests("/api/v2/logs").get(0).body()).contains("\"message\":\"a\"");
    }
  }
}
