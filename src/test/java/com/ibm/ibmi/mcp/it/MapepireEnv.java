package com.ibm.ibmi.mcp.it;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.ibmi.mcp.config.SourceConfig;
import com.ibm.ibmi.mcp.util.DotEnv;

/**
 * Shared env gating for live Mapepire integration tests.
 *
 * <p>Reads {@code DB2i_*} from {@code .env} overlaid by process env (process wins),
 * matching how the server and smoke test resolve credentials.
 */
final class MapepireEnv {

  private static final Logger log = LoggerFactory.getLogger(MapepireEnv.class);

  private static final Path ENV_FILE = Path.of(".env");

  private MapepireEnv() {}

  /** Merged environment: {@code .env} file entries overlaid by process env. */
  static Map<String, String> environment() {
    return DotEnv.environment(ENV_FILE);
  }

  /**
   * Skips (does not fail) the calling test when required {@code DB2i_*} credentials
   * are absent. Call at the start of every {@code *IT} test method.
   */
  static void assumeAvailable() {
    Map<String, String> env = environment();
    String host = trimToNull(env.get("DB2i_HOST"));
    String user = trimToNull(env.get("DB2i_USER"));
    String pass = trimToNull(env.get("DB2i_PASS"));
    boolean available = host != null && user != null && pass != null;
    if (!available) {
      log.warn("Skipping Mapepire IT: DB2i_HOST/USER/PASS not set (process env or .env)");
    }
    Assumptions.assumeTrue(available,
        "DB2i_HOST, DB2i_USER, and DB2i_PASS must be set (process env or .env) for live Mapepire ITs");
  }

  static String host() {
    return required("DB2i_HOST");
  }

  static String user() {
    return required("DB2i_USER");
  }

  static String password() {
    return required("DB2i_PASS");
  }

  static int port() {
    String raw = trimToNull(environment().get("DB2i_PORT"));
    if (raw == null) {
      return SourceConfig.DEFAULT_MAPEPIRE_PORT;
    }
    return Integer.parseInt(raw);
  }

  static boolean ignoreUnauthorized() {
    String raw = trimToNull(environment().get("DB2i_IGNORE_UNAUTHORIZED"));
    if (raw == null) {
      return true;
    }
    return Boolean.parseBoolean(raw) || "1".equals(raw);
  }

  /** Builds a {@link SourceConfig} from env vars for the given source name. */
  static SourceConfig sourceConfig(String name) {
    return new SourceConfig(
        name,
        host(),
        port(),
        user(),
        password(),
        ignoreUnauthorized(),
        SourceConfig.DEFAULT_MAX_SIZE,
        SourceConfig.DEFAULT_STARTING_SIZE,
        Map.of());
  }

  private static String required(String key) {
    String value = trimToNull(environment().get(key));
    if (value == null) {
      throw new IllegalStateException(key + " is required for live Mapepire ITs");
    }
    return value;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
