package codes.grow.packages.datadoglogs;

/**
 * A non-fatal warning of a Datadog query; results may be partial (for example code {@code
 * unknown_index}). Absent fields are empty strings.
 *
 * @param code warning code
 * @param title short title
 * @param detail detail text
 */
public record DatadogWarning(String code, String title, String detail) {
  /**
   * Creates a warning; null fields become empty strings.
   *
   * @param code warning code
   * @param title short title
   * @param detail detail text
   */
  public DatadogWarning {
    code = code == null ? "" : code;
    title = title == null ? "" : title;
    detail = detail == null ? "" : detail;
  }
}
