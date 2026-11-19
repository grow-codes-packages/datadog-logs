package codes.grow.packages.datadoglogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadoglogs.core.ConfigException;
import codes.grow.packages.datadoglogs.core.EnvReader;
import codes.grow.packages.datadoglogs.core.Hooks;
import codes.grow.packages.datadoglogs.core.Logger;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.core.Secret;
import codes.grow.packages.datadoglogs.core.TlsOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatadogLogsConfigTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  private static DatadogLogsConfig.Builder cfg() {
    return DatadogLogsConfig.builder().apiKey("api-key-123456");
  }

  @Test
  void sitesResolve() {
    assertThat(DatadogSites.resolve(null).apiUrl()).isEqualTo("https://api.datadoghq.com");
    DatadogSites.SiteUrls eu = DatadogSites.resolve(" DatadogHQ.eu ");
    assertThat(eu.site()).isEqualTo("datadoghq.eu");
    assertThat(eu.intakeUrl()).isEqualTo("https://http-intake.logs.datadoghq.eu");
    assertThat(eu.apiUrl()).isEqualTo("https://api.datadoghq.eu");
    for (String s : DatadogSites.SITES) {
      assertThat(DatadogSites.resolve(s).intakeUrl()).endsWith(s);
    }
    assertThat(DatadogSites.isKnown("us3.datadoghq.com")).isTrue();
    assertThat(DatadogSites.isKnown("app.datadoghq.com")).isFalse();
    assertThat(DatadogSites.normalize("  ")).isEqualTo(DatadogSites.DEFAULT_SITE);
    assertThatThrownBy(() -> DatadogSites.resolve("app.datadoghq.com"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("datadoghq.eu");
  }

  @Test
  void defaultsAndDerivedUrls() {
    DatadogLogsConfig c = cfg().build();
    assertThat(c.site()).isEqualTo("datadoghq.com");
    assertThat(c.intakeUrl()).isEqualTo("https://http-intake.logs.datadoghq.com");
    assertThat(c.apiUrl()).isEqualTo("https://api.datadoghq.com");
    assertThat(c.url()).isEmpty();
    assertThat(c.appKey()).isEmpty();
    assertThat(c.source()).isEqualTo("grow-sdk");
    assertThat(c.tags()).isEmpty();
    assertThat(c.service()).isEmpty();
    assertThat(c.env()).isEmpty();
    assertThat(c.version()).isEmpty();
    assertThat(c.hostname()).isEmpty();
    assertThat(c.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(c.totalTimeout()).isEqualTo(Duration.ofSeconds(120));
    assertThat(c.retry().maxRetries()).isEqualTo(3);
    assertThat(c.proxy()).isEmpty();
    assertThat(c.userAgentSuffix()).isEmpty();
    assertThat(c.hooks()).isSameAs(Hooks.NONE);
    assertThat(c.logger()).isSameAs(Logger.NOOP);
    assertThat(c.toTransportConfig().baseUrl()).isEqualTo("https://api.datadoghq.com");
    // url sets both; intakeUrl/apiUrl override one each
    DatadogLogsConfig u = cfg().url("http://fake:80/").build();
    assertThat(u.intakeUrl()).isEqualTo("http://fake:80/");
    assertThat(u.apiUrl()).isEqualTo("http://fake:80/");
    DatadogLogsConfig split = cfg().url("http://a").intakeUrl("http://i").build();
    assertThat(split.intakeUrl()).isEqualTo("http://i");
    assertThat(split.apiUrl()).isEqualTo("http://a");
    DatadogLogsConfig both =
        cfg().site("nowhere.example").intakeUrl("http://i").apiUrl("http://a").build();
    assertThat(both.site()).isEqualTo("nowhere.example");
    assertThat(both.apiUrl()).isEqualTo("http://a");
  }

  @Test
  void core_config_env_precedence() {
    Map<String, String> env = new HashMap<>();
    env.put("DD_API_KEY", "dd-api");
    env.put("DD_APPLICATION_KEY", "dd-app-2");
    env.put("DD_SITE", "datadoghq.eu");
    env.put("DD_SERVICE", "dd-svc");
    env.put("DD_ENV", "dd-env");
    env.put("DD_VERSION", "9.9");
    env.put("DD_TAGS", "team:a, b:c  team:a");
    env.put("DD_HOSTNAME", "dd-host");
    DatadogLogsConfig vendor = DatadogLogsConfig.fromEnv(env);
    assertThat(vendor.apiKey().map(Secret::reveal)).contains("dd-api");
    assertThat(vendor.appKey().map(Secret::reveal)).contains("dd-app-2");
    assertThat(vendor.site()).isEqualTo("datadoghq.eu");
    assertThat(vendor.service()).contains("dd-svc");
    assertThat(vendor.env()).contains("dd-env");
    assertThat(vendor.version()).contains("9.9");
    assertThat(vendor.tags()).containsExactly("team:a", "b:c");
    assertThat(vendor.hostname()).contains("dd-host");

    env.put("DD_APP_KEY", "dd-app-1");
    env.put(P + "API_KEY", "grow-api");
    env.put(P + "SITE", "us3.datadoghq.com");
    env.put(P + "URL", "http://from-env:1");
    env.put(P + "INTAKE_URL", "http://intake:2");
    env.put(P + "API_URL", "http://api:3");
    env.put(P + "SERVICE", "grow-svc");
    env.put(P + "SOURCE", "java");
    env.put(P + "CONNECT_TIMEOUT", "1500");
    env.put(P + "REQUEST_TIMEOUT", "2s");
    env.put(P + "TOTAL_TIMEOUT", "1m");
    env.put(P + "MAX_RETRIES", "5");
    env.put(P + "CA_CERT", "/tmp/ca.pem");
    env.put(P + "CLIENT_CERT", "/tmp/c.pem");
    env.put(P + "CLIENT_KEY", "/tmp/k.pem");
    env.put(P + "INSECURE_SKIP_VERIFY", "on");
    env.put(P + "PROXY", "http://proxy:1");
    DatadogLogsConfig grow = DatadogLogsConfig.fromEnv(env);
    assertThat(grow.apiKey().map(Secret::reveal)).contains("grow-api");
    assertThat(grow.appKey().map(Secret::reveal)).contains("dd-app-1");
    assertThat(grow.site()).isEqualTo("us3.datadoghq.com");
    assertThat(grow.url()).contains("http://from-env:1");
    assertThat(grow.intakeUrl()).isEqualTo("http://intake:2");
    assertThat(grow.apiUrl()).isEqualTo("http://api:3");
    assertThat(grow.service()).contains("grow-svc");
    assertThat(grow.source()).isEqualTo("java");
    assertThat(grow.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(grow.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(grow.totalTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(grow.retry().maxRetries()).isEqualTo(5);
    assertThat(grow.tls().insecureSkipVerify()).isTrue();
    assertThat(grow.tls().caCertPath()).isPresent();
    assertThat(grow.proxy()).contains("http://proxy:1");

    DatadogLogsConfig explicit =
        DatadogLogsConfig.builder()
            .apiKey(Secret.of("explicit-api"))
            .appKey(Secret.of("explicit-app"))
            .site("datadoghq.com")
            .url("http://explicit:2")
            .intakeUrl("http://ei")
            .apiUrl("http://ea")
            .service("x-svc")
            .env("x-env")
            .version("x-ver")
            .tags(List.of("x:1", "y:2 z:3"))
            .hostname("x-host")
            .source("x-src")
            .connectTimeout(Duration.ofSeconds(9))
            .requestTimeout(Duration.ofSeconds(9))
            .totalTimeout(Duration.ofSeconds(9))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://p2:1")
            .fromEnv(EnvReader.of(env))
            .build();
    assertThat(explicit.apiKey().map(Secret::reveal)).contains("explicit-api");
    assertThat(explicit.appKey().map(Secret::reveal)).contains("explicit-app");
    assertThat(explicit.site()).isEqualTo("datadoghq.com");
    assertThat(explicit.url()).contains("http://explicit:2");
    assertThat(explicit.intakeUrl()).isEqualTo("http://ei");
    assertThat(explicit.service()).contains("x-svc");
    assertThat(explicit.env()).contains("x-env");
    assertThat(explicit.version()).contains("x-ver");
    assertThat(explicit.tags()).containsExactly("x:1", "y:2", "z:3");
    assertThat(explicit.hostname()).contains("x-host");
    assertThat(explicit.source()).isEqualTo("x-src");
    assertThat(explicit.retry().maxRetries()).isZero();
    assertThat(explicit.tls().insecureSkipVerify()).isFalse();
    assertThat(explicit.proxy()).contains("http://p2:1");

    // invalid values name the variable, never the value
    Map<String, String> bad = Map.of("DD_API_KEY", "k", P + "TOTAL_TIMEOUT", "forever");
    assertThatThrownBy(() -> DatadogLogsConfig.fromEnv(bad))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "TOTAL_TIMEOUT")
        .message()
        .doesNotContain("forever");
    Map<String, String> badRetries = Map.of("DD_API_KEY", "k", P + "MAX_RETRIES", "-1");
    assertThatThrownBy(() -> DatadogLogsConfig.fromEnv(badRetries))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "MAX_RETRIES");
    // the process environment normally has no key: either outcome is fine, never another error
    try {
      assertThat(DatadogLogsConfig.fromEnv()).isNotNull();
    } catch (ConfigException e) {
      assertThat(e.getMessage()).contains("apiKey");
    }
  }

  @Test
  void parseTags() {
    assertThat(DatadogLogsConfig.parseTags(null)).isEmpty();
    assertThat(DatadogLogsConfig.parseTags(" a:1,,b:2 a:1\tc ")).containsExactly("a:1", "b:2", "c");
    assertThat(cfg().tags("env:x team:y").build().tags()).containsExactly("env:x", "team:y");
  }

  @Test
  void validation() {
    assertThatThrownBy(() -> DatadogLogsConfig.builder().build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("apiKey is required");
    assertThatThrownBy(() -> DatadogLogsConfig.builder().apiKey(" ").build())
        .hasMessageContaining("apiKey must not be blank");
    Secret cleared = Secret.of("gone");
    cleared.clear();
    assertThatThrownBy(() -> DatadogLogsConfig.builder().apiKey(cleared).build())
        .hasMessageContaining("apiKey must not be blank");
    assertThatThrownBy(() -> cfg().appKey("").build()).hasMessageContaining("appKey");
    Secret clearedApp = Secret.of("gone");
    clearedApp.clear();
    assertThatThrownBy(() -> cfg().appKey(clearedApp).build()).hasMessageContaining("appKey");
    assertThatThrownBy(() -> cfg().site("app.datadoghq.com").build())
        .hasMessageContaining("not a known Datadog site");
    assertThatThrownBy(() -> cfg().site("x.example").intakeUrl("http://i").build())
        .hasMessageContaining("not a known Datadog site");
    assertThatThrownBy(() -> cfg().url("ftp://x").build()).hasMessageContaining("intakeUrl");
    assertThatThrownBy(() -> cfg().apiUrl("http://[").build()).hasMessageContaining("apiUrl");
    assertThatThrownBy(() -> cfg().apiUrl("mailto:x").build()).hasMessageContaining("apiUrl");
    assertThatThrownBy(() -> cfg().connectTimeout(Duration.ZERO).build())
        .hasMessageContaining("connectTimeout");
    assertThatThrownBy(() -> cfg().requestTimeout(Duration.ofSeconds(-1)).build())
        .hasMessageContaining("requestTimeout");
    assertThatThrownBy(() -> cfg().totalTimeout(Duration.ZERO).build())
        .hasMessageContaining("totalTimeout");
    assertThatThrownBy(() -> cfg().tls(TlsOptions.builder().clientCertPem("x").build()).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().proxy("relative").build()).hasMessageContaining("proxy");
    assertThatThrownBy(() -> cfg().proxy("http://[").build()).hasMessageContaining("proxy");
    assertThat(cfg().appKey((String) null).build().appKey()).isEmpty();
    assertThat(DatadogLogsConfig.builder().apiKey((String) null).appKey("a")).isNotNull();
  }

  @Test
  void core_redaction_config() {
    DatadogLogsConfig c =
        DatadogLogsConfig.builder()
            .apiKey("api-very-secret")
            .appKey("app-very-secret")
            .url("http://user:pw@h:1")
            .header("DD-API-KEY", "api-very-secret")
            .proxy("http://u:proxypass@p:3128")
            .userAgentSuffix("app/1")
            .build();
    assertThat(c.toString())
        .doesNotContain("api-very-secret")
        .doesNotContain("app-very-secret")
        .doesNotContain("proxypass")
        .doesNotContain(":pw@")
        .contains("apiKey=[REDACTED]")
        .contains("appKey=[REDACTED]");
    assertThat(cfg().build().toString()).contains("appKey=null").contains("proxy=null");
    assertThat(c.knownSecrets()).containsExactly("api-very-secret", "app-very-secret");
    assertThat(c.toTransportConfig().knownSecrets())
        .containsExactly("api-very-secret", "app-very-secret");
    c.appKey().get().clear();
    assertThat(c.knownSecrets()).containsExactly("api-very-secret");
  }
}
