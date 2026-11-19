package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/** Mock Datadog server for unit tests: raw (gunzipped) request bodies, per-path answer queues. */
final class DdServer implements AutoCloseable {
  /** One canned answer. */
  record Answer(int status, String body, Map<String, String> headers, long delayMs) {}

  /** One recorded request. */
  record Req(
      String method, String path, Map<String, List<String>> headers, byte[] raw, String body) {
    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue().get(0);
        }
      }
      return null;
    }

    JsonNode json() {
      return Json.tree(body.getBytes(StandardCharsets.UTF_8));
    }
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, ConcurrentLinkedQueue<Answer>> queues = new ConcurrentHashMap<>();
  private final Map<String, Answer> fallbacks = new ConcurrentHashMap<>();
  private final List<Req> requests = new CopyOnWriteArrayList<>();

  DdServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  DdServer on(String path, int status, String body, String... headers) {
    return onDelayed(path, 0, status, body, headers);
  }

  DdServer onDelayed(String path, long delayMs, int status, String body, String... headers) {
    queues
        .computeIfAbsent(path, k -> new ConcurrentLinkedQueue<>())
        .add(new Answer(status, body, pairs(headers), delayMs));
    return this;
  }

  DdServer always(String path, int status, String body, String... headers) {
    fallbacks.put(path, new Answer(status, body, pairs(headers), 0));
    return this;
  }

  DdServer fixture(String path, String name) {
    JsonNode f = Fixtures.load(name);
    JsonNode resp = f.get("response");
    List<String> h = new ArrayList<>();
    resp.path("headers")
        .properties()
        .forEach(
            e -> {
              if (!e.getKey().equals("content-length")) {
                h.add(e.getKey());
                h.add(e.getValue().asText());
              }
            });
    return on(
        path, resp.get("status").asInt(), resp.get("body").toString(), h.toArray(new String[0]));
  }

  private static Map<String, String> pairs(String... headers) {
    Map<String, String> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i + 1 < headers.length; i += 2) {
      m.put(headers[i], headers[i + 1]);
    }
    return m;
  }

  List<Req> requests() {
    return new ArrayList<>(requests);
  }

  List<Req> requests(String path) {
    return requests.stream().filter(r -> r.path().equals(path)).toList();
  }

  private void handle(HttpExchange ex) throws IOException {
    try {
      byte[] raw = ex.getRequestBody().readAllBytes();
      byte[] decoded = raw;
      if ("gzip".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Content-Encoding"))) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
          decoded = in.readAllBytes();
        }
      }
      String path = ex.getRequestURI().getPath();
      requests.add(
          new Req(
              ex.getRequestMethod(),
              path,
              Map.copyOf(ex.getRequestHeaders()),
              raw,
              new String(decoded, StandardCharsets.UTF_8)));
      ConcurrentLinkedQueue<Answer> q = queues.get(path);
      Answer a = q == null ? null : q.poll();
      if (a == null) {
        a =
            fallbacks.getOrDefault(
                path, new Answer(404, "{\"errors\":[\"Not found\"]}", Map.of(), 0));
      }
      if (a.delayMs() > 0) {
        Thread.sleep(a.delayMs());
      }
      a.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
      if (!ex.getResponseHeaders().containsKey("Content-Type")) {
        ex.getResponseHeaders().add("Content-Type", "application/json");
      }
      byte[] b = a.body().getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(a.status(), b.length == 0 ? -1 : b.length);
      if (b.length > 0) {
        try (OutputStream os = ex.getResponseBody()) {
          os.write(b);
        }
      }
    } catch (Exception e) {
      // client went away
    } finally {
      ex.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
