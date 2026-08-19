package com.ibm.ibmi.mcp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SecretFilePermissionsTest {

  private static final Logger log = LoggerFactory.getLogger(SecretFilePermissionsTest.class);

  @Test
  void isProductionIsCaseInsensitive() {
    assertTrue(SecretFilePermissions.isProduction(Map.of("MCP_SERVER_ENV", "production")));
    assertTrue(SecretFilePermissions.isProduction(Map.of("MCP_SERVER_ENV", "Production")));
    assertFalse(SecretFilePermissions.isProduction(Map.of("MCP_SERVER_ENV", "dev")));
    assertFalse(SecretFilePermissions.isProduction(Map.of()));
  }

  @Test
  void isSoleEnvPlaceholderMatchesWholeValue() {
    assertTrue(SecretFilePermissions.isSoleEnvPlaceholder("${DB2i_PASS}"));
    assertTrue(SecretFilePermissions.isSoleEnvPlaceholder("  ${DB2i_PASS}  "));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("hunter2"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("prefix-${DB2i_PASS}"));
    assertFalse(SecretFilePermissions.isSoleEnvPlaceholder("${A}${B}"));
  }

  @Test
  void isSecretEnvKeyMatchesDb2iCredentials() {
    assertTrue(SecretFilePermissions.isSecretEnvKey("DB2i_PASS"));
    assertTrue(SecretFilePermissions.isSecretEnvKey("db2i_password"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("DB2i_HOST"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("MCP_SERVER_ENV"));
    assertFalse(SecretFilePermissions.isSecretEnvKey("API_TOKEN"));
  }

  @Test
  void ownerOnlyFilePasses(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("secrets.env");
    Files.writeString(file, "DB2i_PASS=secret\n");
    assumePosix(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

    SecretFilePermissions.enforceOwnerOnly(file, Map.of(), log);
  }

  @Test
  void groupReadableFileFailsInProduction(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("secrets.env");
    Files.writeString(file, "DB2i_PASS=secret\n");
    assumePosix(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));

    IllegalStateException e = assertThrows(
        IllegalStateException.class,
        () -> SecretFilePermissions.enforceOwnerOnly(
            file, Map.of("MCP_SERVER_ENV", "production"), log));
    assertTrue(e.getMessage().contains("group/world readable"));
    assertTrue(e.getMessage().contains(file.toAbsolutePath().toString()));
  }

  @Test
  void worldReadableFileWarnsWhenNotProduction(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("secrets.env");
    Files.writeString(file, "DB2i_PASS=secret\n");
    assumePosix(file);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    SecretFilePermissions.enforceOwnerOnly(file, Map.of(), log);
  }

  static void assumePosix(Path path) {
    Assumptions.assumeTrue(
        path.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX file permissions required");
  }
}
