package com.ibm.ibmi.mcp.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvTest {

  @Test
  void secretEnvAt0600Loads(@TempDir Path dir) throws Exception {
    Path envFile = dir.resolve(".env");
    Files.writeString(envFile, "DB2i_PASS=secret\n");
    SecretFilePermissionsTest.assumePosix(envFile);
    Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-------"));

    Map<String, String> env = DotEnv.environment(envFile);
    assertEquals("secret", env.get("DB2i_PASS"));
  }

  @Test
  void secretEnvAt0644WarnsUnlessProcessIsProduction(@TempDir Path dir) throws Exception {
    assumeProcessNotProduction();
    Path envFile = dir.resolve(".env");
    Files.writeString(envFile, "DB2i_PASS=secret\n");
    SecretFilePermissionsTest.assumePosix(envFile);
    Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-r--r--"));

    Map<String, String> env = assertDoesNotThrow(() -> DotEnv.environment(envFile));
    assertEquals("secret", env.get("DB2i_PASS"));
  }

  @Test
  void secretEnvAt0644FailsWhenFileSetsProduction(@TempDir Path dir) throws Exception {
    assumeProcessNotProduction();
    Path envFile = dir.resolve(".env");
    Files.writeString(envFile, """
        DB2i_PASS=secret
        MCP_SERVER_ENV=production
        """);
    SecretFilePermissionsTest.assumePosix(envFile);
    Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-r--r--"));

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> DotEnv.environment(envFile));
    assertTrue(e.getMessage().contains("group/world readable"));
  }

  @Test
  void nonSecretEnvAt0644Loads(@TempDir Path dir) throws Exception {
    Path envFile = dir.resolve(".env");
    Files.writeString(envFile, "YAML_AUTO_RELOAD=false\n");
    SecretFilePermissionsTest.assumePosix(envFile);
    Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-r--r--"));

    Map<String, String> env = DotEnv.environment(envFile);
    assertEquals("false", env.get("YAML_AUTO_RELOAD"));
  }

  private static void assumeProcessNotProduction() {
    String value = System.getenv("MCP_SERVER_ENV");
    Assumptions.assumeFalse(
        value != null && "production".equalsIgnoreCase(value.trim()),
        "Process MCP_SERVER_ENV=production would fail this 0644 secret-file case");
  }
}
