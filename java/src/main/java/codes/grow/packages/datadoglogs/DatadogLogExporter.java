package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.DatadogLogsException;
import codes.grow.packages.datadoglogs.core.HttpTransport;
import codes.grow.packages.datadoglogs.core.TransportRequest;
import codes.grow.packages.datadoglogs.core.ValidationException;
import codes.grow.packages.datadoglogs.logging.ExportOptions;
import codes.grow.packages.datadoglogs.logging.ExportResult;
import codes.grow.packages.datadoglogs.logging.LogExporter;
import codes.grow.packages.datadoglogs.logging.LogRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

/**
 * {@link LogExporter} for {@code POST {intake}/api/v2/logs} (API.md section 5; DECISIONS 4, 7).
 *
 * <p>Maps records to Datadog JSON, truncates oversized messages, splits batches at 1000 entries / 5
 * MB, gzips when asked and sends with header {@code DD-API-KEY} only (the application key is never
 * sent to the intake). The intake POST is retried like an idempotent call (429, 5xx, network
 * errors; duplicates are possible after an ambiguous failure).
 */
public final class DatadogLogExporter implements LogExporter {
  /** Intake path. */
  public static final String INTAKE_PATH = "/api/v2/logs";

  private final DatadogHttp http;
  private final Limits limits;

  DatadogLogExporter(DatadogHttp http, Limits limits) {
    this.http = http;
    this.limits = limits;
  }

  /**
   * Request limits; values above the Datadog limits are capped.
   *
   * @param maxEntriesPerRequest entries per request, at most 1000
   * @param maxPayloadBytes uncompressed bytes per request, at most 5 000 000
   * @param maxLogBytes serialized bytes per entry before truncation, at most 1 000 000
   */
  public record Limits(int maxEntriesPerRequest, int maxPayloadBytes, int maxLogBytes) {
    /**
     * Creates limits, capped at the Datadog limits.
     *
     * @param maxEntriesPerRequest entries per request
     * @param maxPayloadBytes payload bytes
     * @param maxLogBytes bytes per entry
     * @throws ValidationException when a value is below 1
     */
    public Limits {
      maxEntriesPerRequest =
          cap(
              maxEntriesPerRequest,
              DatadogLogMapping.MAX_ENTRIES_PER_REQUEST,
              "maxEntriesPerRequest");
      maxPayloadBytes =
          cap(maxPayloadBytes, DatadogLogMapping.MAX_PAYLOAD_BYTES, "maxPayloadBytes");
      maxLogBytes = cap(maxLogBytes, DatadogLogMapping.MAX_LOG_BYTES, "maxLogBytes");
    }

    /**
     * The Datadog limits (1000 entries, 5 000 000 bytes, 1 000 000 bytes per log).
     *
     * @return the defaults
     */
    public static Limits defaults() {
      return new Limits(
          DatadogLogMapping.MAX_ENTRIES_PER_REQUEST,
          DatadogLogMapping.MAX_PAYLOAD_BYTES,
          DatadogLogMapping.MAX_LOG_BYTES);
    }

    private static int cap(int value, int max, String name) {
      if (value < 1) {
        throw new ValidationException("logs.exporter: " + name + " must be a positive integer");
      }
      return Math.min(value, max);
    }
  }

  /**
   * One intake request body before compression.
   *
   * @param body the JSON array text
   * @param entries entries in the array
   * @param bytes UTF-8 bytes of the body
   */
  public record IntakePayload(String body, int entries, int bytes) {}

  /**
   * Result of {@link #encode}.
   *
   * @param payloads request bodies in order
   * @param oversized records whose metadata alone exceeds the per-log limit (never sent)
   * @param truncated entries whose message was truncated ({@code grow.truncated: true})
   */
  public record EncodedBatch(List<IntakePayload> payloads, int oversized, int truncated) {
    /**
     * Creates the result; the list is copied.
     *
     * @param payloads request bodies
     * @param oversized dropped records
     * @param truncated truncated entries
     */
    public EncodedBatch {
      payloads = List.copyOf(payloads);
    }
  }

  /**
   * Returns the request limits.
   *
   * @return the limits
   */
  public Limits limits() {
    return limits;
  }

  /**
   * Maps and splits records into intake request bodies (no I/O).
   *
   * @param records records
   * @return the bodies
   */
  public EncodedBatch encode(List<LogRecord> records) {
    DatadogLogsConfig config = http.config();
    List<IntakePayload> payloads = new ArrayList<>();
    int oversized = 0;
    int truncated = 0;
    StringBuilder body = new StringBuilder("[");
    int entries = 0;
    int bytes = 2;
    for (LogRecord record : records) {
      Map<String, Object> entry = DatadogLogMapping.toDatadogEntry(record, config);
      Optional<DatadogLogMapping.SerializedEntry> s =
          DatadogLogMapping.serializeEntry(entry, limits.maxLogBytes());
      if (s.isEmpty()) {
        oversized++;
        continue;
      }
      DatadogLogMapping.SerializedEntry e = s.get();
      if (e.truncated()) {
        truncated++;
      }
      if (entries > 0
          && (entries >= limits.maxEntriesPerRequest()
              || bytes + e.bytes() + 1 > limits.maxPayloadBytes())) {
        payloads.add(new IntakePayload(body.append(']').toString(), entries, bytes));
        body = new StringBuilder("[");
        entries = 0;
        bytes = 2;
      }
      if (entries > 0) {
        body.append(',');
        bytes++;
      }
      body.append(e.json());
      bytes += e.bytes();
      entries++;
    }
    if (entries > 0) {
      payloads.add(new IntakePayload(body.append(']').toString(), entries, bytes));
    }
    return new EncodedBatch(payloads, oversized, truncated);
  }

  /**
   * Sends the records. Completes with {@code accepted}/{@code rejected} and the error of the first
   * failing request (later chunks count as rejected); never completes exceptionally for service
   * errors.
   *
   * @param records records
   * @param options gzip flag and timeout
   * @return a future with the result
   */
  @Override
  public CompletableFuture<ExportResult> export(List<LogRecord> records, ExportOptions options) {
    EncodedBatch batch = encode(records);
    return sendChunks(batch, 0, 0, records.size(), options);
  }

  private CompletableFuture<ExportResult> sendChunks(
      EncodedBatch batch, int index, int accepted, int total, ExportOptions options) {
    if (index >= batch.payloads().size()) {
      if (batch.oversized() > 0) {
        return CompletableFuture.completedFuture(
            ExportResult.failure(
                accepted,
                batch.oversized(),
                new ValidationException(
                    "logs.send: "
                        + batch.oversized()
                        + " record(s) dropped: metadata exceeds "
                        + limits.maxLogBytes()
                        + " bytes")));
      }
      return CompletableFuture.completedFuture(ExportResult.success(accepted));
    }
    IntakePayload payload = batch.payloads().get(index);
    return sendPayloadAsync(payload.body(), options.gzip())
        .handle(
            (ok, err) -> {
              if (err != null) {
                DatadogLogsException e = HttpTransport.asSdkException(err, "logs.send");
                return CompletableFuture.completedFuture(
                    ExportResult.failure(accepted, total - accepted, e));
              }
              return sendChunks(batch, index + 1, accepted + payload.entries(), total, options);
            })
        .thenCompose(f -> f);
  }

  /**
   * Posts one JSON array body to the intake (blocking).
   *
   * @param body JSON array text
   * @param gzip whether to gzip the body
   * @throws DatadogLogsException the mapped error
   */
  public void sendPayload(String body, boolean gzip) {
    HttpTransport.await(sendPayloadAsync(body, gzip), "logs.send");
  }

  /**
   * Posts one JSON array body to the intake.
   *
   * @param body JSON array text
   * @param gzip whether to gzip the body ({@code Content-Encoding: gzip})
   * @return a future completed on a 2xx answer, failed with the mapped error otherwise
   */
  public CompletableFuture<Void> sendPayloadAsync(String body, boolean gzip) {
    DatadogLogsConfig config = http.config();
    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
    TransportRequest.Builder b =
        TransportRequest.post(DatadogHttp.join(config.intakeUrl(), INTAKE_PATH))
            .header(DatadogHttp.API_KEY_HEADER, config.apiKey().map(s -> s.reveal()).orElse(""))
            .idempotent(true)
            .operation("logs.send");
    if (gzip) {
      b.header("Content-Encoding", "gzip").body(gzip(raw), "application/json");
    } else {
      b.body(raw, "application/json");
    }
    return http.sendAsync(b).thenApply(r -> null);
  }

  static byte[] gzip(byte[] raw) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(raw);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * Nothing to release; the client owns the transport.
   *
   * @return a completed future
   */
  @Override
  public CompletableFuture<Void> shutdown() {
    return CompletableFuture.completedFuture(null);
  }
}
