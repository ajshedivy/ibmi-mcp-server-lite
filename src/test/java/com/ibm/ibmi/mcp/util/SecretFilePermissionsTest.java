package com.ibm.ibmi.mcp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretFilePermissionsTest {

  @Test
  void isProductionIsCaseInsensitive() {
    assertTrue(SecretFilePermissions.isProduction(Map.of("MCP_SERVER_ENV", "production")));
    assertTrue(SecretFilePermissions.isProduction(Map.of("MCP_SERVER_ENV", "Production")));
    assertFalse(SecretFilePermissions.isProduction(Map.of("MCP_SERVER_ENV", "dev")));
    assertFalse(SecretFilePermissions.isProduction(Map.of()));
    assertFalse(SecretFilePermissions.isProduction(null));
  }

  @Test
  void isSoleEnvPlaceholderMatchesWholeValue() {
    assertTrue(SecretFilePermissions.isSoleEnvPlaceholder("${DB2i_PASS}"));
    assertTrue(SecretFilePermissions.isSoleEnvPlaceholder("  ${DB2i_PASS}  "));
    assertTrue(SecretFilePermissions.isSoleEnvPlaceholder("${A}"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("hunter2"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("prefix-${DB2i_PASS}"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("${DB2i_PASS}suffix"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("${A}${B}"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("${}"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder(""));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder(null));
  }

  @Test
  void isSecretEnvKeyMatchesPasswordSuffixes() {
    assertTrue(SecretFilePermissions.isSecretEnvKey("DB2i_PASS"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("db2i_pass"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("API_TOKEN"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("CLIENT_SECRET"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("PASSWORD"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("PASS"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("SECRET"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("TOKEN"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("DB2i_PASSWORD"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("DB2i_HOST"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("MCP_SERVER_ENV"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("BYPASS"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("API_KEY"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("KEY"));
    assertFalse(SecretFilePermissions.isSecretEnvKey(""));
    assertFalse(SecretFilePermissions.isSecretEnvKey(null));
  }

  @Test
  void ownerOnlyFileYieldsNoWarning(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("secrets.env");
    Files.writeString(file, "DB2i_PASS=secret\n");
    assumePosix(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

    assertTrue(SecretFilePermissions.groupOrWorldReadableWarning(file).isEmpty());
  }

  @Test
  void groupReadableFileYieldsWarning(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("secrets.env");
    Files.writeString(file, "DB2i_PASS=secret\n");
    assumePosix(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));

    Optional<String> warning = SecretFilePermissions.groupOrWorldReadableWarning(file);
    assertTrue(warning.isPresent());
    assertTrue(warning.get().contains("group/world readable"));
    assertTrue(warning.get().contains("0640"));
    assertTrue(warning.get().contains("0600"));
    assertTrue(warning.get().contains(file.toAbsolutePath().toString()));
  }

  @Test
  void worldReadableFileYieldsWarning(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("secrets.env");
    Files.writeString(file, "DB2i_PASS=secret\n");
    assumePosix(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    Optional<String> warning = SecretFilePermissions.groupOrWorldReadableWarning(file);
    assertTrue(warning.isPresent());
    assertTrue(warning.get().contains("0644"));
  }

  @Test
  void missingFileYieldsNoWarning(@TempDir Path dir) {
    assertTrue(SecretFilePermissions.groupOrWorldReadableWarning(dir.resolve("missing")).isEmpty());
  }

  @Test
  void toOctalMapsPermissionBits() {
    assertEquals(0600, SecretFilePermissions.toOctal(PosixFilePermissions.fromString("rw-------")));
    assertEquals(0644, SecretFilePermissions.toOctal(PosixFilePermissions.fromString("rw-r--r--")));
    assertEquals(0640, SecretFilePermissions.toOctal(PosixFilePermissions.fromString("rw-r-----")));
    assertEquals(0, SecretFilePermissions.toOctal(Set.of()));
  }

  static void assumePosix(Path path) {
    Assumptions.assumeTrue(
        path.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX file permissions required");
  }
}
