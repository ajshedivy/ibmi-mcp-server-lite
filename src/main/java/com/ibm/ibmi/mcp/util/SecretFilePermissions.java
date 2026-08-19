package com.ibm.ibmi.mcp.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;

/**
 * POSIX permission checks for secret-bearing config files ({@code .env} with IBM i
 * credential keys, tools YAML with a literal {@code sources.*.password}).
 * {@code password: ${VAR}} templates are not secret-bearing. Non-POSIX file systems
 * skip the check.
 */
public final class SecretFilePermissions {

  public static final String SERVER_ENV = "MCP_SERVER_ENV";

  private SecretFilePermissions() {}

  /** {@code MCP_SERVER_ENV=production} (case-insensitive), matching CORS fail-closed. */
  public static boolean isProduction(Map<String, String> env) {
    if (env == null) {
      return false;
    }
    String value = env.get(SERVER_ENV);
    return value != null && "production".equalsIgnoreCase(value.trim());
  }

  /**
   * Whether {@code value} is a sole {@code ${VAR}} placeholder (optional surrounding
   * whitespace). {@code password: ${DB2i_PASS}} matches; {@code hunter2} and
   * {@code prefix-${DB2i_PASS}} do not.
   */
  public static boolean isSoleEnvPlaceholder(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    return trimmed.length() >= 4
        && trimmed.startsWith("${")
        && trimmed.endsWith("}")
        && trimmed.indexOf('}') == trimmed.length() - 1;
  }

  /** Whether a {@code .env} key holds an IBM i credential loaded from disk. */
  public static boolean isSecretEnvKey(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    String upper = key.toUpperCase(Locale.ROOT);
    return upper.equals("DB2I_PASS") || upper.equals("DB2I_PASSWORD");
  }

  /**
   * Warn, or fail in production, when {@code file} is group- or world-readable.
   * No-op on non-POSIX file systems, missing files, or owner-only reads.
   */
  public static void enforceOwnerOnly(Path file, Map<String, String> env, Logger log) {
    groupOrWorldReadableWarning(file).ifPresent(message -> {
      if (isProduction(env)) {
        throw new IllegalStateException(message);
      }
      log.warn("{}", message);
    });
  }

  /**
   * Warning text when {@code file} is group- or world-readable. Empty on non-POSIX
   * file systems, missing files, or owner-only reads.
   */
  static Optional<String> groupOrWorldReadableWarning(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    Set<PosixFilePermission> perms;
    try {
      perms = Files.getPosixFilePermissions(file);
    } catch (UnsupportedOperationException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read permissions for " + file, e);
    }
    if (!perms.contains(PosixFilePermission.GROUP_READ)
        && !perms.contains(PosixFilePermission.OTHERS_READ)) {
      return Optional.empty();
    }
    String symbolic = PosixFilePermissions.toString(perms);
    return Optional.of(
        "Secret-bearing config file '" + file.toAbsolutePath()
            + "' is group/world readable (" + symbolic
            + "). Restrict to owner-only (chmod 600).");
  }
}
