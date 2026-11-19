package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.ConfigException;
import codes.grow.packages.datadoglogs.core.EnvReader;
import codes.grow.packages.datadoglogs.core.Hooks;
import codes.grow.packages.datadoglogs.core.Logger;
import codes.grow.packages.datadoglogs.core.Redaction;
import codes.grow.packages.datadoglogs.core.RetryPolicy;
import codes.grow.packages.datadoglogs.core.Secret;
import codes.grow.packages.datadoglogs.core.TlsOptions;
import codes.grow.packages.datadoglogs.core.TransportConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable configuration of a {@link DatadogLogsClient} (SPEC section 2.2, API.md section 2).
 *
 * <p>Build one with {@link #builder()} or read it from the environment with {@link #fromEnv()}.
 * Precedence: explicit builder value &gt; {@code GROW_DATADOG_LOGS_<NAME>} &gt; vendor variables
 * ({@code DD_API_KEY}, {@code DD_APP_KEY}/{@code DD_APPLICATION_KEY}, {@code DD_SITE}, {@code
 * DD_SERVICE}, {@code DD_ENV}, {@code DD_VERSION}, {@code DD_TAGS}, {@code DD_HOSTNAME}) &gt;
 * default. {@link #toString()} never prints credentials.
 *
 * <p>Requests go to two hosts derived from {@link #site()}: the logs intake and the API. {@code
 * url} sets both at once (tests, proxies, fakeintake); {@code intakeUrl} and {@code apiUrl}
 * override one each.
 *
 * <pre>{@code
 * DatadogLogsConfig cfg = DatadogLogsConfig.builder()
 *     .apiKey(System.getenv("DD_API_KEY"))
 *     .site("datadoghq.eu")
 *     .service("checkout")
 *     .build();
 * }</pre>
 */
public final class DatadogLogsConfig {
  /** Default {@code ddsource} of every log (DECISIONS 9). */
  public static final String DEFAULT_SOURCE = "grow-sdk";

  static final String[] API_KEY_VENDOR_VARS = {"DD_API_KEY"};
  static final String[] APP_KEY_VENDOR_VARS = {"DD_APP_KEY", "DD_APPLICATION_KEY"};
  static final String[] SITE_VENDOR_VARS = {"DD_SITE"};
  static final String[] SERVICE_VENDOR_VARS = {"DD_SERVICE"};
  static final String[] ENV_VENDOR_VARS = {"DD_ENV"};
  static final String[] VERSION_VENDOR_VARS = {"DD_VERSION"};
  static final String[] TAGS_VENDOR_VARS = {"DD_TAGS"};
  static final String[] HOSTNAME_VENDOR_VARS = {"DD_HOSTNAME"};

  private final Secret apiKey;
  private final Secret appKey;
  private final String site;
  private final String url;
  private final String intakeUrl;
  private final String apiUrl;
  private final String service;
  private final String env;
  private final String version;
  private final List<String> tags;
  private final String hostname;
  private final String source;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration totalTimeout;
  private final RetryPolicy retry;
  private final TlsOptions tls;
  private final String proxy;
  private final Map<String, String> headers;
  private final String userAgentSuffix;
  private final Hooks hooks;
  private final Logger logger;

  private DatadogLogsConfig(Builder b) {
    this.apiKey = b.apiKey;
    this.appKey = b.appKey;
    this.site = DatadogSites.normalize(b.site);
    this.url = blankToNull(b.url);
    DatadogSites.SiteUrls derived = DatadogSites.isKnown(site) ? DatadogSites.resolve(site) : null;
    String intake = blankToNull(b.intakeUrl);
    String api = blankToNull(b.apiUrl);
    this.intakeUrl =
        intake != null ? intake : url != null ? url : derived != null ? derived.intakeUrl() : null;
    this.apiUrl = api != null ? api : url != null ? url : derived != null ? derived.apiUrl() : null;
    this.service = blankToNull(b.service);
    this.env = blankToNull(b.env);
    this.version = blankToNull(b.version);
    this.tags = List.copyOf(b.tags);
    this.hostname = blankToNull(b.hostname);
    String src = blankToNull(b.source);
    this.source = src != null ? src : DEFAULT_SOURCE;
    this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : Duration.ofSeconds(10);
    this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : Duration.ofSeconds(30);
    this.totalTimeout = b.totalTimeout != null ? b.totalTimeout : Duration.ofSeconds(120);
    this.retry = b.retry != null ? b.retry : RetryPolicy.defaults();
    this.tls = b.tls != null ? b.tls : TlsOptions.defaults();
    this.proxy = b.proxy;
    this.headers = Map.copyOf(b.headers);
    this.userAgentSuffix = b.userAgentSuffix;
    this.hooks = b.hooks != null ? b.hooks : Hooks.NONE;
    this.logger = b.logger != null ? b.logger : Logger.NOOP;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  /**
   * Starts an empty builder (every field at its default).
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Reads the configuration from the process environment.
   *
   * @return the validated configuration
   * @throws ConfigException when a variable holds an invalid value or the API key is missing (the
   *     message names the variable, never the value)
   */
  public static DatadogLogsConfig fromEnv() {
    return builder().fromEnv(EnvReader.system()).build();
  }

  /**
   * Reads the configuration from an explicit variable map (tests).
   *
   * @param env variable map
   * @return the validated configuration
   */
  public static DatadogLogsConfig fromEnv(Map<String, String> env) {
    return builder().fromEnv(EnvReader.of(env)).build();
  }

  /**
   * Parses Datadog tags: {@code key:value} items separated by commas and/or whitespace (the {@code
   * DD_TAGS} convention). Empty items are dropped and duplicates removed, order kept.
   *
   * @param value tag list text, may be null
   * @return the tags
   */
  public static List<String> parseTags(String value) {
    List<String> out = new ArrayList<>();
    if (value == null) {
      return out;
    }
    for (String tag : value.split("[\\s,]+")) {
      if (!tag.isEmpty() && !out.contains(tag)) {
        out.add(tag);
      }
    }
    return out;
  }

  /**
   * Checks the rules: known site (unless both base URLs are set), http(s) URLs, required non-blank
   * API key, non-blank application key, positive timeouts, TLS pairs and the proxy URL.
   *
   * @throws ConfigException when a rule is violated
   */
  public void validate() {
    List<String> problems = new ArrayList<>();
    if (intakeUrl == null || apiUrl == null) {
      problems.add(
          "site is not a known Datadog site (set one of "
              + String.join(", ", DatadogSites.SITES)
              + " or both intakeUrl and apiUrl)");
    } else {
      if (!isHttpUrl(intakeUrl)) {
        problems.add("intakeUrl must use http or https and include a host");
      }
      if (!isHttpUrl(apiUrl)) {
        problems.add("apiUrl must use http or https and include a host");
      }
    }
    if (apiKey == null) {
      problems.add("apiKey is required (GROW_DATADOG_LOGS_API_KEY or DD_API_KEY)");
    } else if (apiKey.isCleared() || apiKey.reveal().isBlank()) {
      problems.add("apiKey must not be blank");
    }
    if (appKey != null && (appKey.isCleared() || appKey.reveal().isBlank())) {
      problems.add("appKey must not be blank");
    }
    checkPositive(problems, "connectTimeout", connectTimeout);
    checkPositive(problems, "requestTimeout", requestTimeout);
    checkPositive(problems, "totalTimeout", totalTimeout);
    if (proxy != null && !isAbsolute(proxy)) {
      problems.add("proxy must be an absolute URL");
    }
    if (!problems.isEmpty()) {
      throw new ConfigException("config: " + String.join("; ", problems));
    }
    tls.validate();
  }

  private static boolean isHttpUrl(String value) {
    try {
      URI u = new URI(value);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static boolean isAbsolute(String value) {
    try {
      return new URI(value).getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static void checkPositive(List<String> problems, String name, Duration d) {
    if (d.isZero() || d.isNegative()) {
      problems.add(name + " must be positive");
    }
  }

  /**
   * Translates the common fields into transport settings. The transport base URL is {@link
   * #apiUrl()}; intake requests use absolute URLs.
   *
   * @return the transport configuration
   */
  public TransportConfig toTransportConfig() {
    return TransportConfig.builder(apiUrl)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .totalTimeout(totalTimeout)
        .retry(retry)
        .tls(tls)
        .proxy(proxy)
        .headers(headers)
        .userAgentSuffix(userAgentSuffix)
        .hooks(hooks)
        .logger(logger)
        .knownSecrets(this::knownSecrets)
        .build();
  }

  /**
   * Credential values to redact from error messages and logs.
   *
   * @return the API and application key values that are set
   */
  public Collection<String> knownSecrets() {
    List<String> out = new ArrayList<>(2);
    for (Secret s : new Secret[] {apiKey, appKey}) {
      if (s != null && !s.isCleared() && !s.isEmpty()) {
        out.add(s.reveal());
      }
    }
    return out;
  }

  /**
   * Returns the Datadog API key (header {@code DD-API-KEY}).
   *
   * @return the key, or empty (never empty after {@link #validate()})
   */
  public Optional<Secret> apiKey() {
    return Optional.ofNullable(apiKey);
  }

  /**
   * Returns the Datadog application key (header {@code DD-APPLICATION-KEY}, query API only).
   *
   * @return the key, or empty
   */
  public Optional<Secret> appKey() {
    return Optional.ofNullable(appKey);
  }

  /**
   * Returns the normalized Datadog site.
   *
   * @return the site, default {@link DatadogSites#DEFAULT_SITE}
   */
  public String site() {
    return site;
  }

  /**
   * Returns the explicit base URL for both hosts.
   *
   * @return the URL, or empty
   */
  public Optional<String> url() {
    return Optional.ofNullable(url);
  }

  /**
   * Returns the effective logs intake base URL.
   *
   * @return the URL ({@code intakeUrl}, else {@code url}, else derived from the site)
   */
  public String intakeUrl() {
    return intakeUrl;
  }

  /**
   * Returns the effective API base URL.
   *
   * @return the URL ({@code apiUrl}, else {@code url}, else derived from the site)
   */
  public String apiUrl() {
    return apiUrl;
  }

  /**
   * Returns the default {@code service} of every log.
   *
   * @return the service, or empty
   */
  public Optional<String> service() {
    return Optional.ofNullable(service);
  }

  /**
   * Returns the environment, sent as tag {@code env:<value>}.
   *
   * @return the environment, or empty
   */
  public Optional<String> env() {
    return Optional.ofNullable(env);
  }

  /**
   * Returns the version, sent as tag {@code version:<value>}.
   *
   * @return the version, or empty
   */
  public Optional<String> version() {
    return Optional.ofNullable(version);
  }

  /**
   * Returns the tags added to every log.
   *
   * @return immutable list of {@code key:value} strings
   */
  public List<String> tags() {
    return tags;
  }

  /**
   * Returns the default {@code hostname} of every log.
   *
   * @return the hostname, or empty
   */
  public Optional<String> hostname() {
    return Optional.ofNullable(hostname);
  }

  /**
   * Returns the {@code ddsource} of every log.
   *
   * @return the source, default {@link #DEFAULT_SOURCE}
   */
  public String source() {
    return source;
  }

  /**
   * Returns the connect timeout.
   *
   * @return the timeout
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /**
   * Returns the per-attempt timeout.
   *
   * @return the timeout
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the total timeout per call including retries.
   *
   * @return the timeout
   */
  public Duration totalTimeout() {
    return totalTimeout;
  }

  /**
   * Returns the retry policy.
   *
   * @return the policy
   */
  public RetryPolicy retry() {
    return retry;
  }

  /**
   * Returns the TLS options.
   *
   * @return the options
   */
  public TlsOptions tls() {
    return tls;
  }

  /**
   * Returns the explicit proxy URL.
   *
   * @return the proxy, or empty
   */
  public Optional<String> proxy() {
    return Optional.ofNullable(proxy);
  }

  /**
   * Returns the extra request headers.
   *
   * @return immutable map
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns the user agent suffix.
   *
   * @return the suffix, or empty
   */
  public Optional<String> userAgentSuffix() {
    return Optional.ofNullable(userAgentSuffix);
  }

  /**
   * Returns the hooks.
   *
   * @return the hooks
   */
  public Hooks hooks() {
    return hooks;
  }

  /**
   * Returns the logger.
   *
   * @return the logger
   */
  public Logger logger() {
    return logger;
  }

  @Override
  public String toString() {
    return "DatadogLogsConfig{site="
        + site
        + ", intakeUrl="
        + Redaction.redactUrl(intakeUrl)
        + ", apiUrl="
        + Redaction.redactUrl(apiUrl)
        + ", apiKey="
        + (apiKey == null ? "null" : Redaction.REDACTED)
        + ", appKey="
        + (appKey == null ? "null" : Redaction.REDACTED)
        + ", service="
        + service
        + ", env="
        + env
        + ", version="
        + version
        + ", tags="
        + tags
        + ", hostname="
        + hostname
        + ", source="
        + source
        + ", connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", retry="
        + retry
        + ", tls="
        + tls
        + ", proxy="
        + (proxy == null ? "null" : Redaction.redactUrl(proxy))
        + ", headers="
        + Redaction.redactHeaders(headers)
        + ", userAgentSuffix="
        + userAgentSuffix
        + "}";
  }

  /**
   * Builder of the configuration.
   *
   * <p>Unset fields take the environment (when {@link #fromEnv(EnvReader)} is called) or the
   * default.
   */
  public static final class Builder {
    private Secret apiKey;
    private Secret appKey;
    private String site;
    private String url;
    private String intakeUrl;
    private String apiUrl;
    private String service;
    private String env;
    private String version;
    private List<String> tags = new ArrayList<>();
    private boolean tagsSet;
    private String hostname;
    private String source;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration totalTimeout;
    private RetryPolicy retry;
    private TlsOptions tls;
    private String proxy;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String userAgentSuffix;
    private Hooks hooks;
    private Logger logger;

    private Builder() {}

    /**
     * Fills every field that was not set explicitly from the environment.
     *
     * @param reader environment reader
     * @return this builder
     * @throws ConfigException when a variable holds an invalid value
     */
    public Builder fromEnv(EnvReader reader) {
      if (apiKey == null) {
        apiKey = reader.string("API_KEY", API_KEY_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      if (appKey == null) {
        appKey = reader.string("APP_KEY", APP_KEY_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      site = site != null ? site : reader.string("SITE", SITE_VENDOR_VARS).orElse(null);
      url = url != null ? url : reader.string("URL").orElse(null);
      intakeUrl = intakeUrl != null ? intakeUrl : reader.string("INTAKE_URL").orElse(null);
      apiUrl = apiUrl != null ? apiUrl : reader.string("API_URL").orElse(null);
      service =
          service != null ? service : reader.string("SERVICE", SERVICE_VENDOR_VARS).orElse(null);
      env = env != null ? env : reader.string("ENV", ENV_VENDOR_VARS).orElse(null);
      version =
          version != null ? version : reader.string("VERSION", VERSION_VENDOR_VARS).orElse(null);
      if (!tagsSet) {
        reader.string("TAGS", TAGS_VENDOR_VARS).ifPresent(t -> tags = parseTags(t));
      }
      hostname =
          hostname != null
              ? hostname
              : reader.string("HOSTNAME", HOSTNAME_VENDOR_VARS).orElse(null);
      source = source != null ? source : reader.string("SOURCE").orElse(null);
      if (connectTimeout == null) {
        connectTimeout = reader.duration("CONNECT_TIMEOUT").orElse(null);
      }
      if (requestTimeout == null) {
        requestTimeout = reader.duration("REQUEST_TIMEOUT").orElse(null);
      }
      if (totalTimeout == null) {
        totalTimeout = reader.duration("TOTAL_TIMEOUT").orElse(null);
      }
      if (retry == null && reader.integer("MAX_RETRIES").isPresent()) {
        int n = reader.integer("MAX_RETRIES").getAsInt();
        if (n < 0) {
          String variable = EnvReader.DEFAULT_PREFIX + "MAX_RETRIES";
          throw new ConfigException("config: invalid value in " + variable + " (expected >= 0)");
        }
        retry = RetryPolicy.builder().maxRetries(n).build();
      }
      if (tls == null) {
        TlsOptions.Builder t = TlsOptions.builder();
        reader.string("CA_CERT").ifPresent(p -> t.caCertPath(Paths.get(p)));
        reader.string("CLIENT_CERT").ifPresent(p -> t.clientCertPath(Paths.get(p)));
        reader.string("CLIENT_KEY").ifPresent(p -> t.clientKeyPath(Paths.get(p)));
        reader.bool("INSECURE_SKIP_VERIFY").ifPresent(t::insecureSkipVerify);
        tls = t.build();
      }
      if (proxy == null) {
        proxy = reader.string("PROXY").orElse(null);
      }
      return this;
    }

    /**
     * Sets the Datadog API key (required by every call).
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(Secret apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the Datadog API key (required by every call).
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey == null ? null : Secret.of(apiKey);
      return this;
    }

    /**
     * Sets the Datadog application key (query API only; never sent to the intake).
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(Secret appKey) {
      this.appKey = appKey;
      return this;
    }

    /**
     * Sets the Datadog application key (query API only; never sent to the intake).
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(String appKey) {
      this.appKey = appKey == null ? null : Secret.of(appKey);
      return this;
    }

    /**
     * Sets the Datadog site, for example {@code datadoghq.eu} (default {@code datadoghq.com}).
     *
     * @param site site, see {@link DatadogSites#SITES}
     * @return this builder
     */
    public Builder site(String site) {
      this.site = site;
      return this;
    }

    /**
     * Sets one base URL for both the intake and the API (tests, proxies, fakeintake).
     *
     * @param url {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Overrides the logs intake base URL.
     *
     * @param intakeUrl {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder intakeUrl(String intakeUrl) {
      this.intakeUrl = intakeUrl;
      return this;
    }

    /**
     * Overrides the API base URL.
     *
     * @param apiUrl {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder apiUrl(String apiUrl) {
      this.apiUrl = apiUrl;
      return this;
    }

    /**
     * Sets the default {@code service} (used when a record has no {@code service.name} resource).
     *
     * @param service service name
     * @return this builder
     */
    public Builder service(String service) {
      this.service = service;
      return this;
    }

    /**
     * Sets the environment, sent as tag {@code env:<value>}.
     *
     * @param env environment
     * @return this builder
     */
    public Builder env(String env) {
      this.env = env;
      return this;
    }

    /**
     * Sets the version, sent as tag {@code version:<value>}.
     *
     * @param version version
     * @return this builder
     */
    public Builder version(String version) {
      this.version = version;
      return this;
    }

    /**
     * Replaces the tags added to every log.
     *
     * @param tags {@code key:value} strings
     * @return this builder
     */
    public Builder tags(List<String> tags) {
      this.tags = new ArrayList<>();
      for (String t : tags) {
        this.tags.addAll(parseTags(t));
      }
      this.tagsSet = true;
      return this;
    }

    /**
     * Replaces the tags from one string separated by commas or spaces.
     *
     * @param tags tag list text
     * @return this builder
     */
    public Builder tags(String tags) {
      this.tags = parseTags(tags);
      this.tagsSet = true;
      return this;
    }

    /**
     * Sets the default {@code hostname} (used when a record has no {@code host.name} resource).
     *
     * @param hostname host name
     * @return this builder
     */
    public Builder hostname(String hostname) {
      this.hostname = hostname;
      return this;
    }

    /**
     * Sets the {@code ddsource} of every log (default {@code grow-sdk}).
     *
     * @param source source
     * @return this builder
     */
    public Builder source(String source) {
      this.source = source;
      return this;
    }

    /**
     * Sets the connect timeout (default 10 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      this.connectTimeout = d;
      return this;
    }

    /**
     * Sets the per-attempt timeout (default 30 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      this.requestTimeout = d;
      return this;
    }

    /**
     * Sets the total timeout per call including retries (default 120 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      this.totalTimeout = d;
      return this;
    }

    /**
     * Sets the retry policy (default {@link RetryPolicy#defaults()}).
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Sets the TLS options; environment TLS variables are then ignored.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets an explicit proxy URL; environment proxies ({@code HTTPS_PROXY}) are never used.
     *
     * @param proxy proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Adds a header sent with every request.
     *
     * @param name header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the text appended to the {@code User-Agent}.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      this.userAgentSuffix = suffix;
      return this;
    }

    /**
     * Sets request hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Sets the logger (default silent).
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Builds and validates the configuration.
     *
     * @return the configuration
     * @throws ConfigException when a rule is violated
     */
    public DatadogLogsConfig build() {
      DatadogLogsConfig c = new DatadogLogsConfig(this);
      c.validate();
      return c;
    }
  }
}
