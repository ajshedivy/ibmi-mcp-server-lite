package com.ibm.ibmi.mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class YamlConfigLoaderTest {

  private static final String SAMPLE = """
      sources:
        ibmi-system:
          host: ${TEST_HOST}
          user: ${TEST_USER}
          password: ${TEST_PASS}
          port: 8076
          ignore-unauthorized: true

      tools:
        system_status:
          source: ibmi-system
          description: "System status"
          statement: SELECT * FROM TABLE(QSYS2.SYSTEM_STATUS()) X
        active_jobs:
          source: ibmi-system
          description: "Active jobs"
          statement: SELECT * FROM TABLE(QSYS2.ACTIVE_JOB_INFO()) A FETCH FIRST :limit ROWS ONLY
          parameters:
            - name: limit
              type: integer
              default: 10
        disabled_tool:
          enabled: false
          source: ibmi-system
          description: "Disabled"
          statement: SELECT 1 FROM SYSIBM.SYSDUMMY1

      toolsets:
        performance:
          title: "Performance"
          tools:
            - system_status
            - active_jobs
      """;

  private final YamlConfigLoader loader = new YamlConfigLoader(
      Map.of("TEST_HOST", "myhost.example.com", "TEST_USER", "alice", "TEST_PASS", "secret"));

  @Test
  void parsesSourcesToolsAndToolsets() {
    ToolsConfig config = loader.parse(SAMPLE);

    SourceConfig source = config.sources().get("ibmi-system");
    assertEquals("myhost.example.com", source.host());
    assertEquals("alice", source.user());
    assertEquals("secret", source.password());
    assertEquals(8076, source.port());
    assertTrue(source.ignoreUnauthorized());

    assertEquals(3, config.tools().size());
    SqlToolConfig tool = config.tools().get("active_jobs");
    assertEquals(1, tool.parameters().size());
    assertEquals("integer", tool.parameters().get(0).type());
    assertEquals(10, tool.parameters().get(0).defaultValue());

    assertEquals(java.util.List.of("performance"), config.toolsetsForTool("system_status"));
  }

  @Test
  void missingEnvVarKeepsPlaceholderVerbatim() {
    YamlConfigLoader emptyEnv = new YamlConfigLoader(Map.of());
    assertEquals("host: ${NOPE}", emptyEnv.interpolateEnvVars("host: ${NOPE}"));
  }

  @Test
  void unknownSourceReferenceFails() {
    String yaml = """
        sources:
          a:
            host: h
            user: u
            password: p
        tools:
          t:
            source: missing
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(yaml));
    assertTrue(e.getMessage().contains("unknown source 'missing'"));
  }

  @Test
  void enabledToolWithoutStatementFails() {
    String yaml = """
        sources:
          a:
            host: h
            user: u
            password: p
        tools:
          t:
            source: a
            description: d
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(yaml));
    assertTrue(e.getMessage().contains("non-empty statement"));
  }

  @Test
  void toolsetReferencingUnknownToolFails() {
    String yaml = """
        sources:
          a:
            host: h
            user: u
            password: p
        tools:
          t:
            source: a
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          ts:
            tools: [nope]
        """;
    assertThrows(ConfigException.class, () -> loader.parse(yaml));
  }

  @Test
  void selectToolsHonorsEnabledFlagAndToolsetFilter() {
    ToolsConfig config = loader.parse(SAMPLE);

    Map<String, SqlToolConfig> all = config.selectTools(java.util.Set.of());
    assertFalse(all.containsKey("disabled_tool"));
    assertEquals(2, all.size());

    Map<String, SqlToolConfig> filtered = config.selectTools(java.util.Set.of("performance"));
    assertEquals(2, filtered.size());

    Map<String, SqlToolConfig> none = config.selectTools(java.util.Set.of("does-not-exist"));
    assertTrue(none.isEmpty());
  }
}
