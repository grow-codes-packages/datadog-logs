package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.logging.BatchOptions;
import codes.grow.packages.datadoglogs.logging.BatchProcessor;
import codes.grow.packages.datadoglogs.logging.BatchStats;
import codes.grow.packages.datadoglogs.logging.BatchingLogClient;
import codes.grow.packages.datadoglogs.logging.LogClient;
import codes.grow.packages.datadoglogs.logging.LogEntry;
import codes.grow.packages.datadoglogs.logging.RedactionProcessor;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link LogClient} that ships records to the Datadog logs intake through a {@link
 * DatadogLogExporter} and a background {@link BatchProcessor}. Create it with {@link
 * DatadogLogsClient#logClient(Options)}; {@link DatadogLogsClient#close()} flushes and closes it.
 *
 * <pre>{@code
 * DatadogLogClient logs = client.logClient();
 * logs.logger("checkout").info("user signed in", Map.of("user", Map.of("id", 42)));
 * logs.flush();
 * }</pre>
 */
public final class DatadogLogClient implements LogClient {
  private final BatchingLogClient delegate;
  private final DatadogLogExporter exporter;

  DatadogLogClient(BatchingLogClient delegate, DatadogLogExporter exporter) {
    this.delegate = delegate;
    this.exporter = exporter;
  }

  /**
   * Returns the intake exporter used by this client.
   *
   * @return the exporter
   */
  public DatadogLogExporter exporter() {
    return exporter;
  }

  /**
   * Returns the batch processor (queue, stats).
   *
   * @return the processor
   */
  public BatchProcessor processor() {
    return delegate.processor();
  }

  /**
   * Returns the resource merged into every record.
   *
   * @return immutable map
   */
  public Map<String, Object> resource() {
    return delegate.resource();
  }

  @Override
  public void send(LogEntry entry) {
    delegate.send(entry);
  }

  @Override
  public void sendBatch(Collection<? extends LogEntry> entries) {
    delegate.sendBatch(entries);
  }

  @Override
  public void flush() {
    delegate.flush();
  }

  @Override
  public void flush(Duration timeout) {
    delegate.flush(timeout);
  }

  @Override
  public CompletableFuture<Void> flushAsync(Duration timeout) {
    return delegate.flushAsync(timeout);
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public CompletableFuture<Void> closeAsync() {
    return delegate.closeAsync();
  }

  @Override
  public BatchStats stats() {
    return delegate.stats();
  }

  /**
   * Options of {@link DatadogLogsClient#logClient(Options)}: batching (capped at the intake limits:
   * 1000 records, 5 000 000 bytes per batch), resource (merged over the configured {@code service},
   * {@code version}, {@code env} and {@code hostname}), redaction and intake limits.
   */
  public static final class Options {
    private final BatchOptions batchOptions;
    private final Map<String, Object> resource;
    private final RedactionProcessor redaction;
    private final DatadogLogExporter.Limits limits;

    private Options(Builder b) {
      this.batchOptions = b.batchOptions;
      this.resource = Map.copyOf(b.resource);
      this.redaction = b.redaction;
      this.limits = b.limits;
    }

    /**
     * Starts a builder with the defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * The defaults.
     *
     * @return default options
     */
    public static Options defaults() {
      return builder().build();
    }

    /**
     * Returns the batch options.
     *
     * @return the options
     */
    public BatchOptions batchOptions() {
      return batchOptions;
    }

    /**
     * Returns the extra resource attributes.
     *
     * @return immutable map
     */
    public Map<String, Object> resource() {
      return resource;
    }

    /**
     * Returns the redaction processor (null disables redaction).
     *
     * @return the processor, may be null
     */
    public RedactionProcessor redaction() {
      return redaction;
    }

    /**
     * Returns the intake limits.
     *
     * @return the limits
     */
    public DatadogLogExporter.Limits limits() {
      return limits;
    }

    /** Builder of {@link Options}. */
    public static final class Builder {
      private BatchOptions batchOptions = BatchOptions.defaults();
      private final Map<String, Object> resource = new LinkedHashMap<>();
      private RedactionProcessor redaction = RedactionProcessor.defaults();
      private DatadogLogExporter.Limits limits = DatadogLogExporter.Limits.defaults();

      private Builder() {}

      /**
       * Sets the batch options.
       *
       * @param batchOptions options
       * @return this builder
       */
      public Builder batchOptions(BatchOptions batchOptions) {
        this.batchOptions = Objects.requireNonNull(batchOptions, "batchOptions");
        return this;
      }

      /**
       * Adds resource attributes (for example {@code service.name}).
       *
       * @param resource attributes
       * @return this builder
       */
      public Builder resource(Map<String, ?> resource) {
        this.resource.putAll(resource);
        return this;
      }

      /**
       * Sets the redaction processor; null disables redaction.
       *
       * @param redaction processor
       * @return this builder
       */
      public Builder redaction(RedactionProcessor redaction) {
        this.redaction = redaction;
        return this;
      }

      /**
       * Sets the intake request limits.
       *
       * @param limits limits
       * @return this builder
       */
      public Builder limits(DatadogLogExporter.Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        return this;
      }

      /**
       * Builds the options.
       *
       * @return the options
       */
      public Options build() {
        return new Options(this);
      }
    }
  }
}
