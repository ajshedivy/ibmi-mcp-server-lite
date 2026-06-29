package com.ibm.ibmi.mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Collectors;
import java.util.stream.Stream;

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
          fetchAllRows: true
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
    assertEquals(SourceConfig.DEFAULT_MAX_SIZE, source.maxSize());
    assertEquals(SourceConfig.DEFAULT_STARTING_SIZE, source.startingSize());

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

  private static String minimalYamlWithPoolKeys(String poolKeys) {
    String poolSection = poolKeys.lines()
        .map(line -> "    " + line)
        .collect(Collectors.joining("\n"));
    return """
        sources:
          ibmi-system:
            host: h
            user: u
            password: p
        %s
        tools:
          t:
            source: ibmi-system
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """.formatted(poolSection);
  }

  static Stream<Arguments> invalidPoolSizes() {
    return Stream.of(
        Arguments.of("max-size: 0", "max-size must be greater than 0"),
        Arguments.of("starting-size: 0", "starting-size must be greater than 0"),
        Arguments.of("max-size: -1", "max-size must be greater than 0"),
        Arguments.of("max-size: 2\nstarting-size: 5",
            "starting-size must be less than or equal to max-size"));
  }

  @ParameterizedTest
  @MethodSource("invalidPoolSizes")
  void invalidPoolSizesFailAtParseTime(String poolOverrides, String expectedMessage) {
    String yaml = minimalYamlWithPoolKeys(poolOverrides);
    ConfigException e = assertThrows(ConfigException.class, () -> loader.parse(yaml));
    assertTrue(e.getMessage().contains(expectedMessage));
  }

  @Test
  void parsesSourcePoolSizeKeys() {
    String yaml = """
        sources:
          ibmi-system:
            host: h
            user: u
            password: p
            max-size: 5
            starting-size: 1
        tools:
          t:
            source: ibmi-system
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """;
    SourceConfig source = loader.parse(yaml).sources().get("ibmi-system");
    assertEquals(5, source.maxSize());
    assertEquals(1, source.startingSize());
  }

  @Test
  void parsesResponseFormatOptions() {
    String yaml = """
        sources:
          a:
            host: h
            user: u
            password: p
        tools:
          with_markdown:
            source: a
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
            responseFormat: markdown
            tableFormat: ascii
            maxDisplayRows: 25
          defaults:
            source: a
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """;
    ToolsConfig config = loader.parse(yaml);

    SqlToolConfig markdown = config.tools().get("with_markdown");
    assertEquals("markdown", markdown.responseFormat());
    assertEquals("ascii", markdown.tableFormat());
    assertEquals(25, markdown.maxDisplayRows());

    SqlToolConfig defaults = config.tools().get("defaults");
    assertEquals("markdown", defaults.effectiveTableFormat());
    assertEquals(100, defaults.effectiveMaxDisplayRows());
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

  @Test
  void fetchAllRowsParsedCorrectly() {
    ToolsConfig config = loader.parse(SAMPLE);

    SqlToolConfig systemStatus = config.tools().get("system_status");
    assertFalse(systemStatus.isFetchAll(), "system_status should not have fetchAllRows enabled");

    SqlToolConfig activeJobs = config.tools().get("active_jobs");
    assertTrue(activeJobs.isFetchAll(), "active_jobs should have fetchAllRows enabled");
  }

  @Test
  void absentJdbcOptionsBlockYieldsEmptyMap() {
    ToolsConfig config = loader.parse(SAMPLE);
    assertTrue(config.sources().get("ibmi-system").jdbcOptions().isEmpty());
  }

  @Test
  void parsesJdbcOptionsArrayForm() {
    String yaml = """
        sources:
          a:
            host: h
            user: u
            password: p
            jdbc-options:
              libraries: [MYLIB, DEVDATA]
              naming: system
        tools:
          t:
            source: a
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """;
    SourceConfig source = loader.parse(yaml).sources().get("a");
    assertEquals(List.of("MYLIB", "DEVDATA"), source.jdbcOptions().get("libraries"));
    assertEquals("system", source.jdbcOptions().get("naming"));
  }

  @Test
  void parsesJdbcOptionsCsvLibrariesForm() {
    String yaml = """
        sources:
          a:
            host: h
            user: u
            password: p
            jdbc-options:
              libraries: "MYLIB, DEVDATA"
        tools:
          t:
            source: a
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """;
    SourceConfig source = loader.parse(yaml).sources().get("a");
    assertEquals(List.of("MYLIB", "DEVDATA"), source.jdbcOptions().get("libraries"));
  }

  @Test
  void envJdbcOptionsShallowMergeOverYaml() {
    YamlConfigLoader envLoader = new YamlConfigLoader(Map.of(
        "DB2i_JDBC_OPTIONS", "naming=system;libraries=MYLIB,DEVDATA"));
    String yaml = """
        sources:
          a:
            host: h
            user: u
            password: p
            jdbc-options:
              libraries: [OLDLIB]
              naming: sql
              date format: usa
        tools:
          t:
            source: a
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """;
    Map<String, Object> opts = envLoader.parse(yaml).sources().get("a").jdbcOptions();
    assertEquals(List.of("MYLIB", "DEVDATA"), opts.get("libraries"));
    assertEquals("system", opts.get("naming"));
    assertEquals("usa", opts.get("date format"));
  }

  @Test
  void envOnlyJdbcOptionsWithoutYamlBlock() {
    YamlConfigLoader envLoader = new YamlConfigLoader(Map.of(
        "DB2i_JDBC_OPTIONS", "naming=system;libraries=MYLIB,DEVDATA"));
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
        """;
    Map<String, Object> opts = envLoader.parse(yaml).sources().get("a").jdbcOptions();
    assertEquals(List.of("MYLIB", "DEVDATA"), opts.get("libraries"));
    assertEquals("system", opts.get("naming"));
  }

  @Test
  void malformedEnvJdbcOptionsFailsConfigLoad() {
    YamlConfigLoader envLoader = new YamlConfigLoader(Map.of(
        "DB2i_JDBC_OPTIONS", "naming"));
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
        """;
    ConfigException e = assertThrows(ConfigException.class, () -> envLoader.parse(yaml));
    assertTrue(e.getMessage().contains("malformed pair"));
  }
}
