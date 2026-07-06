package com.ibm.ibmi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

  private final ByteArrayOutputStream outputStreamCaptor = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;

  // A complete YAML fixture modeling a tool, a toolset, and a required parameter
  private static final String TEST_YAML_FIXTURE = """
      sources:
        ibmi-system:
          host: localhost
          user: dummy
          password: dummy
      tools:
        active_jobs:
          source: ibmi-system
          description: "Active jobs"
          statement: SELECT * FROM TABLE(QSYS2.ACTIVE_JOB_INFO()) A WHERE JOB_NAME = :job_name
          parameters:
            - name: job_name
              type: string
              required: true
              description: "Job name to filter"
      toolsets:
        performance:
          title: "Performance"
          tools:
            - active_jobs
      """;

  @BeforeEach
  void setUp() {
    System.setOut(new PrintStream(outputStreamCaptor));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  @Test
  void testMainListToolsFeatureWithoutCodeChanges(@TempDir Path tempDir) throws Exception {
    // Write the test fixture string to a real temporary file location
    // This ensures that the main method is able to read from a real file
    Path tempYamlFile = tempDir.resolve("tools-test-fixture.yaml");
    Files.writeString(tempYamlFile, TEST_YAML_FIXTURE);

    // Prepare arguments mimicking command-line entry
    String[] args = new String[] {
        "--tools", tempYamlFile.toAbsolutePath().toString(),
        "--list-tools"
    };

    // Execute the public main method directly
    Main.main(args);

    // Capture console printout and match requirements from instructions
    String output = outputStreamCaptor.toString();

    // Validates that it outputs the expected tool name
    assertTrue(output.contains("active_jobs"), "Console output should contain the tool name");
    
    // Validates that it lists the associated toolset
    assertTrue(output.contains("performance"), "Console output should contain the toolset context");
    
    // Validates that the parameter schema prints the [required] modifier
    assertTrue(output.contains("[required]"), "Console output should contain the [required] parameter marker");
  }

  @Test
  void testMainListToolsetsFromDirectory(@TempDir Path tempDir) throws Exception {
    Path sources = tempDir.resolve("sources.yaml");
    Files.writeString(sources, """
        sources:
          ibmi-system:
            host: localhost
            user: dummy
            password: dummy
        """);
    Path tools = tempDir.resolve("tools.yaml");
    Files.writeString(tools, """
        tools:
          active_jobs:
            source: ibmi-system
            description: "Active jobs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          performance:
            title: "Performance"
            tools: [active_jobs]
        """);

    Main.main(new String[] {"--tools", tempDir.toString(), "--list-toolsets"});

    String output = outputStreamCaptor.toString();
    assertTrue(output.contains("performance"), "Console output should list merged toolset");
    assertTrue(output.contains("active_jobs"), "Console output should list merged tool");
  }

  @Test
  void resolveYamlAutoReload_defaultsToEnabledWhenUnset() {
    assertTrue(Main.resolveYamlAutoReload(Map.of(), false));
  }

  @Test
  void resolveYamlAutoReload_readsTruthyValuesFromMergedEnv() {
    assertTrue(Main.resolveYamlAutoReload(Map.of("YAML_AUTO_RELOAD", "true"), false));
    assertTrue(Main.resolveYamlAutoReload(Map.of("YAML_AUTO_RELOAD", "TRUE"), false));
    assertTrue(Main.resolveYamlAutoReload(Map.of("YAML_AUTO_RELOAD", "1"), false));
  }

  @Test
  void resolveYamlAutoReload_disablesForOtherExplicitValues() {
    assertFalse(Main.resolveYamlAutoReload(Map.of("YAML_AUTO_RELOAD", "false"), false));
    assertFalse(Main.resolveYamlAutoReload(Map.of("YAML_AUTO_RELOAD", "0"), false));
  }

  @Test
  void resolveYamlAutoReload_cliNoReloadOverridesEnv() {
    assertFalse(Main.resolveYamlAutoReload(Map.of("YAML_AUTO_RELOAD", "true"), true));
  }

  @Test
  void resolveYamlAutoReload_readsFromDotEnvFile(@TempDir Path tempDir) throws Exception {
    Path envFile = tempDir.resolve(".env");
    Files.writeString(envFile, "YAML_AUTO_RELOAD=false\n");
    Map<String, String> env = com.ibm.ibmi.mcp.util.DotEnv.environment(envFile);
    assertFalse(Main.resolveYamlAutoReload(env, false));
  }

  @Test
  void resolveConfigValue_readsTransportFromDotEnvFile(@TempDir Path tempDir) throws Exception {
    Path envFile = tempDir.resolve(".env");
    Files.writeString(envFile, """
        MCP_TRANSPORT_TYPE=http
        MCP_HTTP_PORT=9090
        MCP_HTTP_HOST=127.0.0.1
        MCP_HTTP_ENDPOINT_PATH=/api/mcp
        """);
    Map<String, String> env = com.ibm.ibmi.mcp.util.DotEnv.environment(envFile);

    assertEquals("http", Main.resolveConfigValue(null, env, "MCP_TRANSPORT_TYPE", "stdio"));
    assertEquals("9090", Main.resolveConfigValue(null, env, "MCP_HTTP_PORT", "3010"));
    assertEquals("127.0.0.1", Main.resolveConfigValue(null, env, "MCP_HTTP_HOST", "0.0.0.0"));
    assertEquals("/api/mcp", Main.resolveConfigValue(null, env, "MCP_HTTP_ENDPOINT_PATH", "/mcp"));
  }

  @Test
  void resolveConfigValue_cliOverridesDotEnvFile(@TempDir Path tempDir) throws Exception {
    Path envFile = tempDir.resolve(".env");
    Files.writeString(envFile, "MCP_TRANSPORT_TYPE=http\n");
    Map<String, String> env = com.ibm.ibmi.mcp.util.DotEnv.environment(envFile);

    assertEquals("stdio", Main.resolveConfigValue("stdio", env, "MCP_TRANSPORT_TYPE", "stdio"));
  }

  @Test
  void resolveConfigValue_processEnvWinsOverDotEnvFile(@TempDir Path tempDir) throws Exception {
    Path envFile = tempDir.resolve(".env");
    Files.writeString(envFile, "MCP_TRANSPORT_TYPE=http\n");

    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    ProcessBuilder pb = new ProcessBuilder(
        java, "-cp", System.getProperty("java.class.path"),
        ProcessEnvProbe.class.getName(),
        envFile.toAbsolutePath().toString());
    pb.environment().put("MCP_TRANSPORT_TYPE", "stdio");
    Process proc = pb.start();

    assertTrue(proc.waitFor(10, TimeUnit.SECONDS), "subprocess timed out");
    assertEquals(0, proc.exitValue());
    String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals("stdio", output.strip());
  }

  @Test
  void stdioIgnoresInvalidHttpPortInDotEnv(@TempDir Path tempDir) throws Exception {
    Path tempYamlFile = tempDir.resolve("tools-test-fixture.yaml");
    Files.writeString(tempYamlFile, TEST_YAML_FIXTURE);
    Path envFile = tempDir.resolve(".env");
    Files.writeString(envFile, "MCP_HTTP_PORT=not-a-port\n");

    Main.main(new String[] {
        "--tools", tempYamlFile.toAbsolutePath().toString(),
        "--env-file", envFile.toAbsolutePath().toString(),
        "--list-tools"
    });

    assertTrue(outputStreamCaptor.toString().contains("active_jobs"));
  }

  /** Subprocess helper: prints resolved MCP_TRANSPORT_TYPE from merged env. */
  public static final class ProcessEnvProbe {
    private ProcessEnvProbe() {}

    public static void main(String[] args) {
      Map<String, String> env = com.ibm.ibmi.mcp.util.DotEnv.environment(Path.of(args[0]));
      System.out.print(Main.resolveConfigValue(null, env, "MCP_TRANSPORT_TYPE", "stdio"));
    }
  }

  @Test
  void resolveExecuteSql_defaultsToDisabledWhenUnset() {
    assertFalse(Main.resolveExecuteSql(Map.of(), false));
  }

  @Test
  void resolveExecuteSql_readsTruthyValuesFromMergedEnv() {
    assertTrue(Main.resolveExecuteSql(Map.of("IBMI_ENABLE_EXECUTE_SQL", "true"), false));
    assertTrue(Main.resolveExecuteSql(Map.of("IBMI_ENABLE_EXECUTE_SQL", "TRUE"), false));
    assertTrue(Main.resolveExecuteSql(Map.of("IBMI_ENABLE_EXECUTE_SQL", "1"), false));
  }

  @Test
  void resolveExecuteSql_disablesForOtherExplicitValues() {
    assertFalse(Main.resolveExecuteSql(Map.of("IBMI_ENABLE_EXECUTE_SQL", "false"), false));
    assertFalse(Main.resolveExecuteSql(Map.of("IBMI_ENABLE_EXECUTE_SQL", "0"), false));
  }

  @Test
  void resolveExecuteSql_cliFlagOverridesEnv() {
    assertTrue(Main.resolveExecuteSql(Map.of("IBMI_ENABLE_EXECUTE_SQL", "false"), true));
    assertTrue(Main.resolveExecuteSql(Map.of(), true));
  }

  @Test
  void resolveExecuteSqlReadonly_defaultsToEnabledWhenUnset() {
    assertTrue(Main.resolveExecuteSqlReadonly(Map.of()));
  }

  @Test
  void resolveExecuteSqlReadonly_readsTruthyValuesFromMergedEnv() {
    assertTrue(Main.resolveExecuteSqlReadonly(Map.of("IBMI_EXECUTE_SQL_READONLY", "true")));
    assertTrue(Main.resolveExecuteSqlReadonly(Map.of("IBMI_EXECUTE_SQL_READONLY", "1")));
  }

  @Test
  void resolveExecuteSqlReadonly_disablesForOtherExplicitValues() {
    assertFalse(Main.resolveExecuteSqlReadonly(Map.of("IBMI_EXECUTE_SQL_READONLY", "false")));
    assertFalse(Main.resolveExecuteSqlReadonly(Map.of("IBMI_EXECUTE_SQL_READONLY", "0")));
  }

  @Test
  void resolveExecuteSql_readsFromDotEnvFile(@TempDir Path tempDir) throws Exception {
    Path envFile = tempDir.resolve(".env");
    Files.writeString(envFile, "IBMI_ENABLE_EXECUTE_SQL=true\n");
    Map<String, String> env = com.ibm.ibmi.mcp.util.DotEnv.environment(envFile);
    assertTrue(Main.resolveExecuteSql(env, false));
  }
  }
}