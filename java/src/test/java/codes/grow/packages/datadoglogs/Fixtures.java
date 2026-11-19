package codes.grow.packages.datadoglogs;

import codes.grow.packages.datadoglogs.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Loads the recorded prep fixtures from {@code src/test/resources/fixtures}. */
final class Fixtures {
  private Fixtures() {}

  static JsonNode load(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name + ".json")) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      return Json.tree(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String body(String name) {
    return load(name).get("response").get("body").toString();
  }
}
