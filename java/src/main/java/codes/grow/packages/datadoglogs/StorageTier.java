package codes.grow.packages.datadoglogs;

/** Where a Datadog log query runs ({@code filter.storage_tier}). */
public enum StorageTier {
  /** Indexed logs (default). */
  INDEXES("indexes"),
  /** Online archives. */
  ONLINE_ARCHIVES("online-archives"),
  /** Flex logs. */
  FLEX("flex");

  private final String wire;

  StorageTier(String wire) {
    this.wire = wire;
  }

  /**
   * Returns the wire value.
   *
   * @return the value sent as {@code storage_tier}
   */
  public String wireValue() {
    return wire;
  }
}
