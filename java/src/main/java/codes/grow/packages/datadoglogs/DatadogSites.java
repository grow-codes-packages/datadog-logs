package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.ConfigException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Datadog sites and the host resolver (API.md section 1).
 *
 * <p>Every site has two hosts: the logs intake {@code https://http-intake.logs.<site>} and the API
 * {@code https://api.<site>}. The site is the API site, never the UI host ({@code
 * app.datadoghq.com}).
 *
 * <pre>{@code
 * DatadogSites.resolve("datadoghq.eu").intakeUrl(); // "https://http-intake.logs.datadoghq.eu"
 * }</pre>
 */
public final class DatadogSites {
  /** Default Datadog site (US1). */
  public static final String DEFAULT_SITE = "datadoghq.com";

  /** Every site accepted by {@code site} / {@code DD_SITE} (the official OpenAPI servers enum). */
  public static final List<String> SITES =
      List.of(
          "datadoghq.com",
          "us3.datadoghq.com",
          "us5.datadoghq.com",
          "ap1.datadoghq.com",
          "ap2.datadoghq.com",
          "uk1.datadoghq.com",
          "datadoghq.eu",
          "ddog-gov.com",
          "us2.ddog-gov.com");

  private DatadogSites() {}

  /**
   * The two base URLs of a Datadog site.
   *
   * @param site normalized site name
   * @param intakeUrl logs intake base, {@code https://http-intake.logs.<site>}
   * @param apiUrl API base (validate, search, aggregate, indexes), {@code https://api.<site>}
   */
  public record SiteUrls(String site, String intakeUrl, String apiUrl) {
    /**
     * Creates the record.
     *
     * @param site normalized site name
     * @param intakeUrl logs intake base
     * @param apiUrl API base
     */
    public SiteUrls {
      Objects.requireNonNull(site, "site");
      Objects.requireNonNull(intakeUrl, "intakeUrl");
      Objects.requireNonNull(apiUrl, "apiUrl");
    }
  }

  /**
   * Lower-cases and trims a site name; {@code null} or blank gives {@link #DEFAULT_SITE}.
   *
   * @param site site name, may be null
   * @return the normalized name
   */
  public static String normalize(String site) {
    if (site == null || site.isBlank()) {
      return DEFAULT_SITE;
    }
    return site.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Returns whether the site (after {@link #normalize}) is a known Datadog site.
   *
   * @param site site name
   * @return true for a site in {@link #SITES}
   */
  public static boolean isKnown(String site) {
    return SITES.contains(normalize(site));
  }

  /**
   * Resolves a site to its intake and API base URLs.
   *
   * @param site site name such as {@code datadoghq.eu}; null or blank means {@link #DEFAULT_SITE}
   * @return the base URLs
   * @throws ConfigException for an unknown site
   */
  public static SiteUrls resolve(String site) {
    String s = normalize(site);
    if (!SITES.contains(s)) {
      throw new ConfigException(
          "config: site is not a known Datadog site (use one of " + String.join(", ", SITES) + ")");
    }
    return new SiteUrls(s, "https://http-intake.logs." + s, "https://api." + s);
  }
}
