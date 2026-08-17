package com.ibm.ibmi.mcp.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal {@code .env} file reader (KEY=VALUE lines, {@code #} comments, optional quotes).
 * Real process environment variables always win over file entries, matching dotenv's
 * default behavior in the reference Node.js server.
 */
public final class DotEnv {

  private static final Logger log = LoggerFactory.getLogger(DotEnv.class);

  private DotEnv() {}

  /** Environment for ${VAR} interpolation: .env file entries overlaid by process env. */
  public static Map<String, String> environment(Path envFile) {
    Map<String, String> env = new LinkedHashMap<>();
    Map<String, String> fileEntries = Map.of();
    if (envFile != null && Files.isRegularFile(envFile)) {
      fileEntries = parse(envFile);
      env.putAll(fileEntries);
    }
    env.putAll(System.getenv());
    checkSecretFilePermissions(envFile, fileEntries, env);
    return env;
  }

  /**
   * Warn, or fail in production, when a loaded {@code .env} that holds secret keys is
   * group/world readable. Non-POSIX file systems skip the check.
   */
  private static void checkSecretFilePermissions(
      Path envFile, Map<String, String> fileEntries, Map<String, String> mergedEnv) {
    if (envFile == null || fileEntries.isEmpty()) {
      return;
    }
    boolean secretBearing = fileEntries.keySet().stream().anyMatch(SecretFilePermissions::isSecretEnvKey);
    if (!secretBearing) {
      return;
    }
    SecretFilePermissions.groupOrWorldReadableWarning(envFile).ifPresent(message -> {
      if (SecretFilePermissions.isProduction(mergedEnv)) {
        throw new IllegalStateException(message);
      }
      log.warn("{}", message);
    });
  }

  static Map<String, String> parse(Path envFile) {
    Map<String, String> entries = new LinkedHashMap<>();
    try {
      for (String line : Files.readAllLines(envFile)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = trimmed.substring(0, eq).trim();
        String value = trimmed.substring(eq + 1).trim();
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
          value = value.substring(1, value.length() - 1);
        }
        entries.put(key, value);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read env file: " + envFile, e);
    }
    return entries;
  }
}
