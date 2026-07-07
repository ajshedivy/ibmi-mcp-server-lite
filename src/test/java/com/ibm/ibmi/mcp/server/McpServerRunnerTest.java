package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.MergeOptions;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.ParameterConfig;
import com.ibm.ibmi.mcp.config.SecurityConfig;
import com.ibm.ibmi.mcp.config.SourceConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;
import com.ibm.ibmi.mcp.mapepire.SourceManager;
import com.ibm.ibmi.mcp.util.ShutdownGuard;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

class McpServerRunnerTest {

  private McpServerRunner.ServerHandle handle;

  @AfterEach
  void tearDown() {
    if (handle != null) {
      handle.close();
      handle = null;
    }
  }

  @Test
  void serverHandleCloseIsIdempotent() {
    var jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(
            jsonMapper, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()))
        .serverInfo("test", "0")
        .capabilities(ServerCapabilities.builder().build())
        .build();
    McpServerRunner.ServerHandle testHandle = new McpServerRunner.ServerHandle(
        new SourceManager(Map.of()),
        McpServerRunner.ToolSpecContext.create(),
        new ConcurrentHashMap<>(),
        false,
        true);
    testHandle.attachServer(server);
    assertDoesNotThrow(() -> {
      testHandle.close();
      testHandle.close();
    });
  }

  @Test
  void shutdownGuardRunsCleanupOnceWhenTwoPathsFire() throws InterruptedException {
    AtomicBoolean shuttingDown = new AtomicBoolean(false);
    CountDownLatch shutdownLatch = new CountDownLatch(1);
    AtomicInteger cleanupRuns = new AtomicInteger(0);
    Runnable shutdown = ShutdownGuard.once(
        shuttingDown, shutdownLatch, cleanupRuns::incrementAndGet);

    Thread stdinEof = new Thread(shutdown, "stdin-eof");
    Thread shutdownHook = new Thread(shutdown, "shutdown-cleanup");
    stdinEof.start();
    shutdownHook.start();
    stdinEof.join();
    shutdownHook.join();

    assertTrue(shutdownLatch.await(2, TimeUnit.SECONDS));
    assertEquals(1, cleanupRuns.get());
  }

  @Test
  void buildSpec_mapsToolMetadataAndHandler() {
    SqlToolConfig toolConfig = tool("active_jobs", "Active jobs on the system",
        "SELECT * FROM TABLE(QSYS2.ACTIVE_JOB_INFO())",
        List.of(new ParameterConfig(
            "job_name", "string", "Job name", null, true, null, null, null, null, null, null, null)));
    SourceManager sources = sources();
    McpServerRunner.ToolSpecContext ctx = McpServerRunner.ToolSpecContext.create();

    McpServerFeatures.SyncToolSpecification spec =
        McpServerRunner.buildSpec(toolConfig, sources, ctx);

    assertEquals("active_jobs", spec.tool().name());
    assertEquals("Active jobs on the system", spec.tool().description());
    assertNotNull(spec.tool().inputSchema());
    assertNotNull(spec.tool().outputSchema());
    assertTrue(spec.tool().annotations().readOnlyHint());
    assertNotNull(spec.callHandler());
  }

  @Test
  void formatToolTitle_splitsOnUnderscoresAndHyphens() {
    assertEquals("Active Job Info", McpServerRunner.formatToolTitle("active_job_info"));
    assertEquals("My Tool", McpServerRunner.formatToolTitle("my-tool"));
  }

  @Test
  void computeReloadPlan_detectsAddedRemovedAndChangedTools() {
    SqlToolConfig toolA = tool("tool_a", "A", "SELECT 1 FROM SYSIBM.SYSDUMMY1");
    SqlToolConfig toolB = tool("tool_b", "B", "SELECT 2 FROM SYSIBM.SYSDUMMY1");
    SqlToolConfig toolBUpdated = tool("tool_b", "B updated", "SELECT 2 FROM SYSIBM.SYSDUMMY1");
    SqlToolConfig toolC = tool("tool_c", "C", "SELECT 3 FROM SYSIBM.SYSDUMMY1");

    Map<String, SqlToolConfig> registered = Map.of("tool_a", toolA, "tool_b", toolB);
    Map<String, SqlToolConfig> selected = Map.of("tool_b", toolBUpdated, "tool_c", toolC);

    McpServerRunner.ToolReloadPlan plan =
        McpServerRunner.computeReloadPlan(registered, selected);

    assertEquals(Set.of("tool_a", "tool_b"), plan.toRemove());
    assertEquals(Set.of("tool_b", "tool_c"), plan.toAdd());
  }

  @Test
  void computeReloadPlan_isEmptyWhenNothingChanged() {
    SqlToolConfig toolA = tool("tool_a", "A", "SELECT 1 FROM SYSIBM.SYSDUMMY1");
    Map<String, SqlToolConfig> registered = Map.of("tool_a", toolA);

    McpServerRunner.ToolReloadPlan plan =
        McpServerRunner.computeReloadPlan(registered, registered);

    assertTrue(plan.toRemove().isEmpty());
    assertTrue(plan.toAdd().isEmpty());
  }

  @Test
  void reload_returnsFalseWhenNoToolChanges(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);

    handle = startFromYaml(yaml, env);
    assertEquals(Set.of("tool_a"), toolNames(handle));

    assertFalse(McpServerRunner.reload(
        handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertEquals(Set.of("tool_a"), toolNames(handle));
    assertEquals(1, handle.registeredTools().size());
  }

  @Test
  void executeSqlAbsentWhenGateDisabled(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    handle = startFromYaml(yaml, Map.of(), false, true);

    assertEquals(Set.of("tool_a"), toolNames(handle));
    assertFalse(toolNames(handle).contains(BuiltinTools.EXECUTE_SQL_NAME));
    assertFalse(handle.registeredTools().containsKey(BuiltinTools.EXECUTE_SQL_NAME));
  }

  @Test
  void executeSqlRegisteredWhenGateEnabled(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    handle = startFromYaml(yaml, Map.of(), true, true);

    assertTrue(toolNames(handle).contains(BuiltinTools.EXECUTE_SQL_NAME));
    assertTrue(handle.registeredTools().containsKey(BuiltinTools.EXECUTE_SQL_NAME));
    assertEquals("ibmi-system",
        handle.registeredTools().get(BuiltinTools.EXECUTE_SQL_NAME).source());
  }

  @Test
  void executeSqlInputSchemaRequiresSqlProperty() throws Exception {
    String schemaJson = McpServerRunner.ToolSpecContext.create()
        .schemaBuilder()
        .buildInputSchema(BuiltinTools.executeSql("ibmi-system", true).parameters());
    JsonNode schema = new ObjectMapper().readTree(schemaJson);

    assertTrue(schema.get("properties").has("sql"));
    assertEquals("string", schema.get("properties").get("sql").get("type").asText());

    List<String> required = new java.util.ArrayList<>();
    schema.get("required").forEach(n -> required.add(n.asText()));
    assertEquals(List.of("sql"), required);
  }

  @Test
  void validateSelectedTools_acceptsExecuteSqlPlaceholderStatement() {
    SqlToolConfig executeSql = BuiltinTools.executeSql("ibmi-system", true);
    assertDoesNotThrow(() -> McpServerRunner.validateSelectedTools(List.of(executeSql)));
  }

  @Test
  void resolveExecuteSqlSource_picksFirstSourceInMergeOrder(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, """
        sources:
          alpha:
            host: localhost
            user: user
            password: pass
          beta:
            host: localhost
            user: user
            password: pass
        tools:
          tool_a:
            source: alpha
            description: "a"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    ToolsConfig config = new YamlConfigLoader(Map.of()).load(yaml);
    // First key in YAML merge insertion order is the deterministic default.
    assertEquals("alpha", McpServerRunner.resolveExecuteSqlSource(config));
  }

  @Test
  void resolveExecuteSqlSource_throwsWhenNoSources() {
    ToolsConfig config = new ToolsConfig(Map.of(), Map.of(), Map.of());
    IllegalArgumentException e = assertThrows(
        IllegalArgumentException.class,
        () -> McpServerRunner.resolveExecuteSqlSource(config));
    assertTrue(e.getMessage().contains("No sources defined"));
  }

  @Test
  void executeSqlStartupFailsWhenNoSources() {
    ToolsConfig config = new ToolsConfig(Map.of(), Map.of(), Map.of());
    IllegalArgumentException e = assertThrows(
        IllegalArgumentException.class,
        () -> McpServerRunner.startForTests(config, Set.of(), true, true));
    assertTrue(e.getMessage().contains("No sources defined"));
  }

  @Test
  void computeReloadPlan_ignoresRegisteredExecuteSqlBuiltin() {
    SqlToolConfig toolA = tool("tool_a", "A", "SELECT 1 FROM SYSIBM.SYSDUMMY1");
    SqlToolConfig executeSql = BuiltinTools.executeSql("ibmi-system", true);
    Map<String, SqlToolConfig> registered = Map.of(
        "tool_a", toolA,
        BuiltinTools.EXECUTE_SQL_NAME, executeSql);
    Map<String, SqlToolConfig> selected = Map.of("tool_a", toolA);

    McpServerRunner.ToolReloadPlan plan =
        McpServerRunner.computeReloadPlan(registered, selected);

    assertTrue(plan.toRemove().isEmpty());
    assertTrue(plan.toAdd().isEmpty());
  }

  @Test
  void reload_updatesRegisteredTools(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);

    handle = startFromYaml(yaml, env);
    assertEquals(Set.of("tool_a"), toolNames(handle));

    Files.writeString(yaml, yamlWithTools("tool_a", "tool_b"));
    assertTrue(McpServerRunner.reload(
        handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertEquals(Set.of("tool_a", "tool_b"), toolNames(handle));
    assertEquals(2, handle.registeredTools().size());
  }

  @Test
  @Timeout(10)
  void attachYamlWatcher_directoryChangeTriggersMergedReload(@TempDir Path tempDir)
      throws Exception {
    Path toolsA = tempDir.resolve("a.yaml");
    Path toolsB = tempDir.resolve("b.yaml");
    Files.writeString(toolsA, yamlWithTools("tool_a"));
    Files.writeString(toolsB, yamlWithToolsOnly("tool_b"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    String toolsPath = tempDir.toString();

    handle = startFromDirectory(tempDir, env);
    assertEquals(Set.of("tool_a", "tool_b"), toolNames(handle));

    McpServerRunner.attachYamlWatcher(handle, toolsPath, env, mergeOpts, Set.of());

    Files.writeString(toolsA, yamlWithTools("tool_a", "tool_c"));
    await(() -> handle.registeredTools().size() == 3, 5_000);
    assertEquals(Set.of("tool_a", "tool_b", "tool_c"), toolNames(handle));
  }

  @Test
  @Timeout(10)
  void attachYamlWatcher_globChangeTriggersMergedReload(@TempDir Path tempDir) throws Exception {
    Path toolsA = tempDir.resolve("a.yaml");
    Path toolsB = tempDir.resolve("b.yaml");
    Files.writeString(toolsA, yamlWithTools("tool_a"));
    Files.writeString(toolsB, yamlWithToolsOnly("tool_b"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    String glob = tempDir.toString() + "/*.yaml";

    handle = startFromGlob(glob, env);
    assertEquals(Set.of("tool_a", "tool_b"), toolNames(handle));

    McpServerRunner.attachYamlWatcher(handle, glob, env, mergeOpts, Set.of());

    Files.writeString(toolsB, yamlWithToolsOnly("tool_b", "tool_d"));
    await(() -> handle.registeredTools().containsKey("tool_d"), 5_000);
    assertEquals(Set.of("tool_a", "tool_b", "tool_d"), toolNames(handle));
  }

  @Test
  void reload_updatesMergedToolsFromDirectory(@TempDir Path tempDir) throws Exception {
    Path toolsA = tempDir.resolve("a.yaml");
    Path toolsB = tempDir.resolve("b.yaml");
    Files.writeString(toolsA, yamlWithTools("tool_a"));
    Files.writeString(toolsB, yamlWithToolsOnly("tool_b"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);

    handle = startFromDirectory(tempDir, env);
    assertEquals(Set.of("tool_a", "tool_b"), toolNames(handle));

    Files.writeString(toolsA, yamlWithTools("tool_a", "tool_c"));
    assertTrue(McpServerRunner.reload(
        handle, tempDir.toString(), env, mergeOpts, Set.of()));
    assertEquals(Set.of("tool_a", "tool_b", "tool_c"), toolNames(handle));
  }

  @Test
  void reload_swallowsParseErrorsAndKeepsPriorTools(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);

    handle = startFromYaml(yaml, env);
    Set<String> before = toolNames(handle);

    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          broken_tool:
            source: ibmi-system
            description: missing statement
        """);
    assertFalse(McpServerRunner.reload(
        handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertEquals(before, toolNames(handle));
    assertEquals(1, handle.registeredTools().size());
  }

  @Test
  void reload_swallowsNewSourceAndKeepsPriorTools(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);

    handle = startFromYaml(yaml, env);
    Set<String> before = toolNames(handle);

    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
          other-system:
            host: other.example.com
            user: user
            password: pass
        tools:
          tool_on_new_source:
            source: other-system
            description: uses source not in startup SourceManager
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    assertFalse(McpServerRunner.reload(
        handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertEquals(before, toolNames(handle));
    assertEquals(1, handle.registeredTools().size());
  }

  @Test
  void rollbackTools_restoresPriorRegistrationAfterPartialApply(@TempDir Path tempDir)
      throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    Map<String, String> env = Map.of();

    handle = startFromYaml(yaml, env);
    SqlToolConfig toolA = handle.registeredTools().get("tool_a");
    SqlToolConfig toolB = tool("tool_b", "tool_b", "SELECT 1 FROM SYSIBM.SYSDUMMY1");
    Map<String, SqlToolConfig> previous = Map.of("tool_a", toolA);

    McpServerRunner.applyReloadPlan(
        handle,
        new ToolsConfig(Map.of(), Map.of("tool_b", toolB), Map.of()),
        Map.of("tool_b", toolB),
        new McpServerRunner.ToolReloadPlan(Set.of("tool_a"), Set.of("tool_b")));

    assertEquals(Set.of("tool_b"), toolNames(handle));

    McpServerRunner.rollbackTools(handle, previous);

    assertEquals(Set.of("tool_a"), toolNames(handle));
    assertEquals(previous, Map.copyOf(handle.registeredTools()));
  }

  @Test
  void reload_swallowsSecurityValidationAndKeepsPriorTools(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);

    handle = startFromYaml(yaml, env);
    Set<String> before = toolNames(handle);

    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          bad_tool:
            source: ibmi-system
            description: write attempt
            statement: INSERT INTO t VALUES (1)
        """);
    assertFalse(McpServerRunner.reload(
        handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertEquals(before, toolNames(handle));
  }

  private static McpServerRunner.ServerHandle startFromYaml(Path yaml, Map<String, String> env) {
    return startFromYaml(yaml, env, false, true);
  }

  private static McpServerRunner.ServerHandle startFromYaml(
      Path yaml, Map<String, String> env, boolean enableExecuteSql, boolean executeSqlReadonly) {
    ToolsConfig config = new YamlConfigLoader(env).load(yaml);
    return McpServerRunner.startForTests(config, Set.of(), enableExecuteSql, executeSqlReadonly);
  }

  private static McpServerRunner.ServerHandle startFromDirectory(Path dir, Map<String, String> env) {
    ToolsConfig config = new YamlConfigLoader(env).loadAll(dir.toString(), MergeOptions.fromEnv(env));
    return McpServerRunner.startForTests(config, Set.of());
  }

  private static McpServerRunner.ServerHandle startFromGlob(String glob, Map<String, String> env) {
    ToolsConfig config = new YamlConfigLoader(env).loadAll(glob, MergeOptions.fromEnv(env));
    return McpServerRunner.startForTests(config, Set.of());
  }

  private static void await(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
    assertFalse(true, "Condition not met within " + timeoutMs + "ms");
  }

  private static Set<String> toolNames(McpServerRunner.ServerHandle handle) {
    return handle.server().listTools().stream()
        .map(tool -> tool.name())
        .collect(Collectors.toSet());
  }

  private static String yamlWithTools(String... toolNames) {
    StringBuilder tools = new StringBuilder();
    for (String name : toolNames) {
      tools.append("""
            %s:
              source: ibmi-system
              description: "%s"
              statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          """.formatted(name, name));
    }
    return """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
        """ + tools;
  }

  private static String yamlWithToolsOnly(String... toolNames) {
    StringBuilder tools = new StringBuilder();
    for (String name : toolNames) {
      tools.append("""
            %s:
              source: ibmi-system
              description: "%s"
              statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          """.formatted(name, name));
    }
    return "tools:\n" + tools;
  }

  private static SqlToolConfig tool(
      String name, String description, String statement, List<ParameterConfig> parameters) {
    return new SqlToolConfig(
        name, true, "ibmi-system", description, statement, parameters,
        null, null, null, Map.of("readOnlyHint", true), SecurityConfig.DEFAULTS,
        null, null, null, null);
  }

  private static SqlToolConfig tool(String name, String description, String statement) {
    return tool(name, description, statement, List.of());
  }

  private static SourceManager sources() {
    return new SourceManager(Map.of(
        "ibmi-system",
        new SourceConfig("ibmi-system", "localhost", 8076, "user", "pass",
            true, 10, 2, Map.of())));
  }
}
