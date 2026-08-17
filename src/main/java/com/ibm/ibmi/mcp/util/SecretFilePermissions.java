package com.ibm.ibmi.mcp.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * POSIX permission checks for secret-bearing config files ({@code .env} with password
 * keys, tools YAML with a literal {@code sources.*.password}). {@code password: ${VAR}}
 * templates are not secret-bearing. Non-POSIX file systems skip the check.
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

  /**
   * Whether a {@code .env} key looks like a secret (password / pass / secret / token).
   * {@code DB2i_PASS} matches via the {@code _PASS} suffix. {@code BYPASS} and
   * {@code API_KEY} do not.
   */
  public static boolean isSecretEnvKey(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    String upper = key.toUpperCase(Locale.ROOT);
    return upper.equals("PASS")
        || upper.equals("PASSWORD")
        || upper.equals("SECRET")
        || upper.equals("TOKEN")
        || upper.endsWith("_PASS")
        || upper.endsWith("_PASSWORD")
        || upper.endsWith("_SECRET")
        || upper.endsWith("_TOKEN");
  }

  /**
   * Warning text when {@code file} is group- or world-readable. Empty on non-POSIX
   * file systems, missing files, or owner-only reads.
   */
  public static Optional<String> groupOrWorldReadableWarning(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    Set<PosixFilePermission> perms;
    try {
      perms = Files.getPosixFilePermissions(file);
    } catch (UnsupportedOperationException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read permissions for " + file, e);
    }
    if (!perms.contains(PosixFilePermission.GROUP_READ)
        && !perms.contains(PosixFilePermission.OTHERS_READ)) {
      return Optional.empty();
    }
    String symbolic = PosixFilePermissions.toString(perms);
    String octal = String.format("%04o", toOctal(perms));
    return Optional.of(
        "Secret-bearing config file '" + file.toAbsolutePath()
            + "' is group/world readable (" + symbolic + " / " + octal
            + "). Restrict to owner-only (0600).");
  }

  static int toOctal(Set<PosixFilePermission> perms) {
    int mode = 0;
    if (perms.contains(PosixFilePermission.OWNER_READ)) {
      mode |= 0400;
    }
    if (perms.contains(PosixFilePermission.OWNER_WRITE)) {
      mode |= 0200;
    }
    if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
      mode |= 0100;
    }
    if (perms.contains(PosixFilePermission.GROUP_READ)) {
      mode |= 040;
    }
    if (perms.contains(PosixFilePermission.GROUP_WRITE)) {
      mode |= 020;
    }
    if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) {
      mode |= 010;
    }
    if (perms.contains(PosixFilePermission.OTHERS_READ)) {
      mode |= 04;
    }
    if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
      mode |= 02;
    }
    if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
      mode |= 01;
    }
    return mode;
  }
}
