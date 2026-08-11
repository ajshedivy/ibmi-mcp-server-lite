package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.MergeOptions;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;
import com.ibm.ibmi.mcp.server.resources.ToolsetsResourceLogic;
import com.ibm.ibmi.mcp.server.resources.ToolsetsResourceRegistrar;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

class ToolsetsResourcesIntegrationTest {

  private McpServerRunner.ServerHandle handle;

  @AfterEach
  void tearDown() {
    if (handle != null) {
      handle.close();
      handle = null;
    }
  }

  @Test
  void startForTests_listsCatalogAndToolsetUris(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithToolset("discovery", "list_libs"));
    handle = start(yaml);

    Set<String> uris = resourceUris(handle);
    assertTrue(uris.contains("toolsets://"));
    assertTrue(uris.contains("toolsets://discovery"));
    assertEquals(2, uris.size());
  }

  @Test
  void catalogRead_viaRegisteredHandler_returnsJsonWithExpectedFields(@TempDir Path tempDir)
      throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithToolset("discovery", "list_libs"));
    handle = start(yaml);

    ReadResourceResult result = readViaSpec(
        ToolsetsResourceRegistrar.catalogSpec(
            handle.toolSpecContext().mapper(), handle::toolsetsSnapshot),
        ToolsetsResourceLogic.CATALOG_URI);
    TextResourceContents contents = (TextResourceContents) result.contents().get(0);
    assertEquals("application/json", contents.mimeType());

    JsonNode root = new ObjectMapper().readTree(contents.text());
    assertEquals(1, root.get("totalToolsets").asInt());
    assertEquals(1, root.get("totalTools").asInt());
    assertEquals("discovery", root.get("toolsets").get(0).get("name").asText());
    assertEquals("list_libs", root.get("toolsets").get(0).get("tools").get(0).asText());
    assertTrue(root.has("statistics"));
    assertTrue(root.has("timestamp"));
  }

  @Test
  void detailRead_viaRegisteredHandler_returnsToolsInToolset(@TempDir Path tempDir)
      throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithToolset("performance", "system_status", "job_info"));
    handle = start(yaml);

    ReadResourceResult result = readViaSpec(
        ToolsetsResourceRegistrar.toolsetSpec(
            "performance", handle.toolSpecContext().mapper(), handle::toolsetsSnapshot),
        "toolsets://performance");
    JsonNode root = new ObjectMapper().readTree(
        ((TextResourceContents) result.contents().get(0)).text());
    assertEquals(1, root.get("totalToolsets").asInt());
    assertEquals(2, root.get("toolsets").get(0).get("toolCount").asInt());
    assertEquals("system_status", root.get("toolsets").get(0).get("tools").get(0).asText());
  }

  @Test
  void reload_addingToolsetUpdatesResources(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithToolset("discovery", "list_libs"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    handle = start(yaml);
    assertEquals(Set.of("toolsets://", "toolsets://discovery"), resourceUris(handle));

    Files.writeString(yaml, yamlWithTwoToolsets());
    assertTrue(McpServerRunner.reload(handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertEquals(
        Set.of("toolsets://", "toolsets://discovery", "toolsets://performance"),
        resourceUris(handle));
  }

  @Test
  void reload_removingToolsetUpdatesResources(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTwoToolsets());
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    handle = start(yaml);
    assertTrue(resourceUris(handle).contains("toolsets://performance"));

    Files.writeString(yaml, yamlWithToolset("discovery", "list_libs", "system_status", "job_info"));
    assertTrue(McpServerRunner.reload(handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertEquals(Set.of("toolsets://", "toolsets://discovery"), resourceUris(handle));
  }

  @Test
  void reload_toolsetOnlyChangeWithoutToolDiff(@TempDir Path tempDir) throws Exception {
    // Same tools; add a second toolset that references an existing tool.
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          list_libs:
            source: ibmi-system
            description: "libs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          discovery:
            description: "Discovery"
            tools:
              - list_libs
        """);
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    handle = start(yaml);
    assertFalse(resourceUris(handle).contains("toolsets://also"));

    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          list_libs:
            source: ibmi-system
            description: "libs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          discovery:
            description: "Discovery"
            tools:
              - list_libs
          also:
            description: "Also"
            tools:
              - list_libs
        """);
    assertTrue(McpServerRunner.reload(handle, yaml.toString(), env, mergeOpts, Set.of()));
    assertTrue(resourceUris(handle).contains("toolsets://also"));
  }

  @Test
  void reload_toolsetMembershipChangeRefreshesListMetadata(@TempDir Path tempDir)
      throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          system_status:
            source: ibmi-system
            description: "status"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          job_info:
            source: ibmi-system
            description: "jobs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          performance:
            description: "Performance"
            tools:
              - system_status
        """);
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    handle = start(yaml);

    Resource before = resourceByUri(handle, "toolsets://performance");
    assertTrue(before.description().contains("(1 tools)"));

    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          system_status:
            source: ibmi-system
            description: "status"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          job_info:
            source: ibmi-system
            description: "jobs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          performance:
            description: "Performance"
            tools:
              - system_status
              - job_info
        """);
    assertTrue(McpServerRunner.reload(handle, yaml.toString(), env, mergeOpts, Set.of()));

    Resource after = resourceByUri(handle, "toolsets://performance");
    assertTrue(after.description().contains("(2 tools)"),
        () -> "expected refreshed list metadata, got: " + after.description());
  }

  @Test
  void currentToolsetResourceUris_matchesListedToolsetsScheme(@TempDir Path tempDir)
      throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTwoToolsets());
    handle = start(yaml);

    assertEquals(
        Set.of("toolsets://", "toolsets://discovery", "toolsets://performance"),
        McpServerRunner.currentToolsetResourceUris(handle.server()));
  }

  @Test
  void restoreAfterReloadFailure_restoresPreviousResourceUris(@TempDir Path tempDir)
      throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithToolset("discovery", "list_libs"));
    handle = start(yaml);
    ToolsConfig previous = handle.toolsConfig();
    assertEquals(Set.of("toolsets://", "toolsets://discovery"), resourceUris(handle));

    ToolsConfig expanded = new YamlConfigLoader(Map.of()).parse(yamlWithTwoToolsets());
    assertTrue(McpServerRunner.syncToolsetResources(handle, expanded));
    assertTrue(resourceUris(handle).contains("toolsets://performance"));

    Map<String, SqlToolConfig> toolsSnapshot = Map.copyOf(handle.registeredTools());
    McpServerRunner.restoreAfterReloadFailure(handle, toolsSnapshot, false, previous);

    assertEquals(Set.of("toolsets://", "toolsets://discovery"), resourceUris(handle));
    assertEquals(previous.toolsets().keySet(), handle.toolsetsSnapshot().keySet());
  }

  @Test
  void reload_failureAfterSourceApplyRestoresPriorSourcesToolsAndResourceUris(
      @TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithToolset("discovery", "list_libs"));
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    handle = start(yaml);

    Set<String> previousTools = Set.copyOf(handle.registeredTools().keySet());
    var previousSources = handle.sources().snapshotConfigs();
    Set<String> previousUris = resourceUris(handle);

    Files.writeString(
        yaml,
        yamlWithTwoToolsets().replace("host: localhost", "host: replacement.example.com"));

    assertFalse(McpServerRunner.reloadWithHook(
        handle,
        yaml.toString(),
        env,
        mergeOpts,
        Set.of(),
        () -> {
          throw new IllegalStateException("forced failure after source apply");
        }));

    assertEquals(previousSources, handle.sources().snapshotConfigs());
    assertEquals(previousTools, handle.registeredTools().keySet());
    assertEquals(previousUris, resourceUris(handle));
    assertFalse(resourceUris(handle).contains("toolsets://performance"));
  }

  @Test
  void catalogRead_emptyToolsets_throwsMcpError(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          list_libs:
            source: ibmi-system
            description: "libs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    handle = start(yaml);
    assertTrue(resourceUris(handle).contains("toolsets://"));

    McpError error = assertThrows(McpError.class,
        () -> ToolsetsResourceRegistrar.read(
            handle.toolSpecContext().mapper(),
            handle::toolsetsSnapshot,
            ToolsetsResourceLogic.CATALOG_URI));
    assertEquals(
        io.modelcontextprotocol.spec.McpSchema.ErrorCodes.INTERNAL_ERROR,
        error.getJsonRpcError().code());
  }

  private static McpServerRunner.ServerHandle start(Path yaml) {
    ToolsConfig config = new YamlConfigLoader(Map.of()).load(yaml);
    return McpServerRunner.startForTests(config, Set.<String>of());
  }

  private static ReadResourceResult readViaSpec(
      McpServerFeatures.SyncResourceSpecification spec, String uri) {
    return spec.readHandler().apply(null, new ReadResourceRequest(uri));
  }

  private static Set<String> resourceUris(McpServerRunner.ServerHandle handle) {
    return McpServerRunner.currentToolsetResourceUris(handle.server());
  }

  private static Resource resourceByUri(McpServerRunner.ServerHandle handle, String uri) {
    return handle.server().listResources().stream()
        .filter(r -> uri.equals(r.uri()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing resource " + uri));
  }

  private static String yamlWithToolset(String toolset, String... tools) {
    StringBuilder toolBlock = new StringBuilder();
    StringBuilder memberBlock = new StringBuilder();
    for (String name : tools) {
      toolBlock.append("""
            %s:
              source: ibmi-system
              description: "%s"
              statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          """.formatted(name, name));
      memberBlock.append("      - ").append(name).append('\n');
    }
    return """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
        """ + toolBlock + """
        toolsets:
          %s:
            description: "%s tools"
            tools:
        """.formatted(toolset, toolset) + memberBlock;
  }

  private static String yamlWithTwoToolsets() {
    return """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
          list_libs:
            source: ibmi-system
            description: "libs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          system_status:
            source: ibmi-system
            description: "status"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          job_info:
            source: ibmi-system
            description: "jobs"
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          discovery:
            description: "Discovery"
            tools:
              - list_libs
          performance:
            description: "Performance"
            tools:
              - system_status
              - job_info
        """;
  }
}
