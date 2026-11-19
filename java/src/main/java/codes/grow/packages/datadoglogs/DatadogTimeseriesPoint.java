package codes.grow.packages.datadoglogs;

import java.time.Instant;

/**
 * One point of a timeseries compute.
 *
 * @param time bucket start
 * @param value value
 */
public record DatadogTimeseriesPoint(Instant time, double value) {}
