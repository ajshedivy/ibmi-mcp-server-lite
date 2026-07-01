package com.ibm.ibmi.mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  void loadReparseReflectsChangedFileText(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, minimalToolsYaml("tool_a", "First description"));
    ToolsConfig first = loader.load(yaml);

    assertEquals("First description", first.tools().get("tool_a").description());
    assertEquals(1, first.tools().size());

    Files.writeString(yaml, minimalToolsYaml("tool_a", "Updated description", "tool_b", "New tool"));
    ToolsConfig second = loader.load(yaml);

    assertEquals("Updated description", second.tools().get("tool_a").description());
    assertEquals("New tool", second.tools().get("tool_b").description());
    assertEquals(2, second.tools().size());
  }

  private static String minimalToolsYaml(String... nameDescriptionPairs) {
    if (nameDescriptionPairs.length % 2 != 0) {
      throw new IllegalArgumentException("Expected name/description pairs");
    }
    StringBuilder tools = new StringBuilder();
    for (int i = 0; i < nameDescriptionPairs.length; i += 2) {
      String name = nameDescriptionPairs[i];
      String description = nameDescriptionPairs[i + 1];
      tools.append("""
            %s:
              source: a
              description: "%s"
              statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          """.formatted(name, description));
    }
    return """
        sources:
          a:
            host: h
            user: u
            password: p
        tools:
        """ + tools;
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

  // -- multi-file merge -----------------------------------------------------------------------

  private static final String MINIMAL_SOURCE = """
      sources:
        shared:
          host: h
          user: u
          password: p
      """;

  private static final String MINIMAL_TOOL = """
      tools:
        query_one:
          source: shared
          description: d
          statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
      """;

  private static void write(Path dir, String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }

  @Test
  void loadsAndMergesDirectory(@TempDir Path dir) throws IOException {
    write(dir, "sources.yaml", MINIMAL_SOURCE);
    write(dir, "tools.yaml", MINIMAL_TOOL);

    ToolsConfig config = loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of()));

    assertTrue(config.sources().containsKey("shared"));
    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void loadsGlobPattern(@TempDir Path dir) throws IOException {
    write(dir, "sources.yaml", MINIMAL_SOURCE);
    write(dir, "tools.yaml", MINIMAL_TOOL);

    String glob = dir.toString() + "/*.yaml";
    ToolsConfig config = loader.loadAll(glob, MergeOptions.fromEnv(Map.of()));

    assertTrue(config.sources().containsKey("shared"));
    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void loadsNestedGlobRecursiveYaml(@TempDir Path dir) throws IOException {
    Path nested = dir.resolve("nested");
    Files.createDirectories(nested);
    write(dir, "sources.yaml", MINIMAL_SOURCE);
    write(nested, "tools.yaml", MINIMAL_TOOL);

    String glob = dir.toString() + "/**/*.yaml";
    ToolsConfig config = loader.loadAll(glob, MergeOptions.fromEnv(Map.of()));

    assertTrue(config.sources().containsKey("shared"));
    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void loadsNestedGlobRecursiveYml(@TempDir Path dir) throws IOException {
    Path nested = dir.resolve("nested");
    Files.createDirectories(nested);
    write(dir, "sources.yml", MINIMAL_SOURCE);
    write(nested, "tools.yml", MINIMAL_TOOL);

    String glob = dir.toString() + "/**/*.yml";
    ToolsConfig config = loader.loadAll(glob, MergeOptions.fromEnv(Map.of()));

    assertTrue(config.sources().containsKey("shared"));
    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void loadsGlobWithBraceAlternation(@TempDir Path dir) throws IOException {
    Path nested = dir.resolve("nested");
    Files.createDirectories(nested);
    write(dir, "sources.yaml", MINIMAL_SOURCE);
    write(nested, "tools.yml", MINIMAL_TOOL);

    String glob = dir.toString() + "/**/*.{yaml,yml}";
    ToolsConfig config = loader.loadAll(glob, MergeOptions.fromEnv(Map.of()));

    assertTrue(config.sources().containsKey("shared"));
    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void expandBraceAlternationSplitsAlternatives() {
    assertEquals(
        List.of("a.yaml", "a.yml"),
        YamlConfigLoader.expandBraceAlternation("a.{yaml,yml}"));
    assertEquals(
        List.of("dir/**/*.yaml", "dir/**/*.yml"),
        YamlConfigLoader.expandBraceAlternation("dir/**/*.{yaml,yml}"));
  }

  @Test
  void expandGlobPatternAddsFlattenedRecursiveVariant() {
    assertEquals(
        List.of("dir/**/*.yaml", "dir/*.yaml"),
        YamlConfigLoader.expandGlobPattern("dir/**/*.yaml"));
    assertEquals(
        List.of("dir/**/*.yaml", "dir/*.yaml", "dir/**/*.yml", "dir/*.yml"),
        YamlConfigLoader.expandGlobPattern("dir/**/*.{yaml,yml}"));
  }

  @Test
  void emptyDirectoryFails(@TempDir Path dir) {
    ConfigException e = assertThrows(
        ConfigException.class, () -> loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of())));
    assertTrue(e.getMessage().contains("No YAML files found in directory"));
  }

  @Test
  void zeroGlobMatchFails(@TempDir Path dir) {
    String glob = dir.toString() + "/*.missing.yaml";
    ConfigException e = assertThrows(
        ConfigException.class, () -> loader.loadAll(glob, MergeOptions.fromEnv(Map.of())));
    assertTrue(e.getMessage().contains("No files found matching pattern"));
  }

  @Test
  void duplicateToolFailsByDefault(@TempDir Path dir) throws IOException {
    write(dir, "a.yaml", MINIMAL_SOURCE + MINIMAL_TOOL);
    write(dir, "b.yaml", MINIMAL_TOOL);

    ConfigException e = assertThrows(
        ConfigException.class, () -> loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of())));
    assertTrue(e.getMessage().contains("Duplicate tool name: query_one"));
    assertTrue(e.getMessage().contains("YAML_ALLOW_DUPLICATE_TOOLS=true"));
  }

  @Test
  void duplicateToolAllowedOverwrites(@TempDir Path dir) throws IOException {
    write(dir, "a.yaml", MINIMAL_SOURCE + """
        tools:
          query_one:
            source: shared
            description: first
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    write(dir, "b.yaml", """
        tools:
          query_one:
            source: shared
            description: second
            statement: SELECT 2 FROM SYSIBM.SYSDUMMY1
        """);

    Map<String, String> env = Map.of("YAML_ALLOW_DUPLICATE_TOOLS", "true");
    YamlConfigLoader mergeLoader = new YamlConfigLoader(env);
    ToolsConfig config = mergeLoader.loadAll(dir.toString(), MergeOptions.fromEnv(env));

    assertEquals("second", config.tools().get("query_one").description());
  }

  @Test
  void duplicateSourceFailsByDefault(@TempDir Path dir) throws IOException {
    write(dir, "a.yaml", MINIMAL_SOURCE);
    write(dir, "b.yaml", MINIMAL_SOURCE + MINIMAL_TOOL);

    ConfigException e = assertThrows(
        ConfigException.class, () -> loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of())));
    assertTrue(e.getMessage().contains("Duplicate source name: shared"));
    assertTrue(e.getMessage().contains("YAML_ALLOW_DUPLICATE_SOURCES=true"));
  }

  @Test
  void duplicateSourceAllowedOverwrites(@TempDir Path dir) throws IOException {
    write(dir, "a.yaml", """
        sources:
          shared:
            host: first-host
            user: u
            password: p
        """);
    write(dir, "b.yaml", """
        sources:
          shared:
            host: second-host
            user: u
            password: p
        tools:
          query_one:
            source: shared
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);

    Map<String, String> env = Map.of("YAML_ALLOW_DUPLICATE_SOURCES", "true");
    YamlConfigLoader mergeLoader = new YamlConfigLoader(env);
    ToolsConfig config = mergeLoader.loadAll(dir.toString(), MergeOptions.fromEnv(env));

    assertEquals("second-host", config.sources().get("shared").host());
  }

  @Test
  void toolsetArraysConcatenated(@TempDir Path dir) throws IOException {
    write(dir, "a.yaml", MINIMAL_SOURCE + """
        tools:
          tool_a:
            source: shared
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          ops:
            tools: [tool_a]
        """);
    write(dir, "b.yaml", """
        tools:
          tool_b:
            source: shared
            description: d
            statement: SELECT 2 FROM SYSIBM.SYSDUMMY1
        toolsets:
          ops:
            tools: [tool_b]
        """);

    ToolsConfig config = loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of()));

    assertEquals(List.of("tool_a", "tool_b"), config.toolsets().get("ops").tools());
  }

  @Test
  void toolsetReplacedWhenMergeArraysFalse(@TempDir Path dir) throws IOException {
    write(dir, "a.yaml", MINIMAL_SOURCE + """
        tools:
          tool_a:
            source: shared
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          ops:
            tools: [tool_a]
        """);
    write(dir, "b.yaml", """
        tools:
          tool_b:
            source: shared
            description: d
            statement: SELECT 2 FROM SYSIBM.SYSDUMMY1
        toolsets:
          ops:
            tools: [tool_b]
        """);

    Map<String, String> env = Map.of("YAML_MERGE_ARRAYS", "false");
    YamlConfigLoader mergeLoader = new YamlConfigLoader(env);
    ToolsConfig config = mergeLoader.loadAll(dir.toString(), MergeOptions.fromEnv(env));

    assertEquals(List.of("tool_b"), config.toolsets().get("ops").tools());
  }

  @Test
  void crossFileSourceReference(@TempDir Path dir) throws IOException {
    write(dir, "tools.yaml", """
        tools:
          query_one:
            source: shared
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    write(dir, "sources.yaml", MINIMAL_SOURCE);

    ToolsConfig config = loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of()));

    assertTrue(config.sources().containsKey("shared"));
    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void crossFileSourceFailsPostMerge(@TempDir Path dir) throws IOException {
    write(dir, "tools.yaml", """
        tools:
          query_one:
            source: missing
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    write(dir, "other.yaml", MINIMAL_SOURCE);

    ConfigException e = assertThrows(
        ConfigException.class, () -> loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of())));
    assertTrue(e.getMessage().contains("unknown source 'missing'"));
  }

  @Test
  void loadAllSingleFileMatchesLoad(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("all.yaml");
    Files.writeString(file, MINIMAL_SOURCE + MINIMAL_TOOL);

    ToolsConfig viaLoad = loader.load(file);
    ToolsConfig viaLoadAll = loader.loadAll(file.toString(), MergeOptions.fromEnv(Map.of()));

    assertEquals(viaLoad.sources().keySet(), viaLoadAll.sources().keySet());
    assertEquals(viaLoad.tools().keySet(), viaLoadAll.tools().keySet());
  }

  @Test
  void loadsNestedDirectoryAndYmlExtension(@TempDir Path dir) throws IOException {
    Path nested = dir.resolve("nested");
    Files.createDirectories(nested);
    write(dir, "sources.yaml", MINIMAL_SOURCE);
    write(nested, "tools.yml", MINIMAL_TOOL);

    ToolsConfig config = loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of()));

    assertTrue(config.sources().containsKey("shared"));
    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void mergeOrderIsDeterministicLastWins(@TempDir Path dir) throws IOException {
    write(dir, "a.yaml", MINIMAL_SOURCE + """
        tools:
          query_one:
            source: shared
            description: from-a
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    write(dir, "z.yaml", """
        tools:
          query_one:
            source: shared
            description: from-z
            statement: SELECT 2 FROM SYSIBM.SYSDUMMY1
        """);

    Map<String, String> env = Map.of("YAML_ALLOW_DUPLICATE_TOOLS", "true");
    YamlConfigLoader mergeLoader = new YamlConfigLoader(env);
    ToolsConfig config = mergeLoader.loadAll(dir.toString(), MergeOptions.fromEnv(env));

    assertEquals("from-z", config.tools().get("query_one").description());
  }

  @Test
  void resolveToolPathsSortsByAbsolutePath(@TempDir Path dir) throws IOException {
    write(dir, "z.yaml", MINIMAL_SOURCE);
    write(dir, "a.yaml", MINIMAL_TOOL);

    List<Path> paths = YamlConfigLoader.resolveToolPaths(dir.toString());

    assertEquals(2, paths.size());
    assertTrue(paths.get(0).toString().endsWith("a.yaml"));
    assertTrue(paths.get(1).toString().endsWith("z.yaml"));
  }

  @Test
  void crossFileToolsetReference(@TempDir Path dir) throws IOException {
    write(dir, "tools.yaml", MINIMAL_SOURCE + """
        tools:
          query_one:
            source: shared
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);
    write(dir, "toolsets.yaml", """
        toolsets:
          ops:
            tools: [query_one]
        """);

    ToolsConfig config = loader.loadAll(dir.toString(), MergeOptions.fromEnv(Map.of()));

    assertEquals(List.of("query_one"), config.toolsets().get("ops").tools());
  }

  @Test
  void validateMergedFalseSkipsPostMergeChecks(@TempDir Path dir) throws IOException {
    write(dir, "tools.yaml", """
        tools:
          query_one:
            source: missing
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        toolsets:
          ops:
            tools: [ghost_tool]
        """);
    write(dir, "other.yaml", MINIMAL_SOURCE);

    Map<String, String> env = Map.of("YAML_VALIDATE_MERGED", "false");
    YamlConfigLoader mergeLoader = new YamlConfigLoader(env);
    ToolsConfig config = mergeLoader.loadAll(dir.toString(), MergeOptions.fromEnv(env));

    assertTrue(config.tools().containsKey("query_one"));
    assertTrue(config.toolsets().containsKey("ops"));
  }

  @Test
  void validateMergedFalseAppliesToSingleFile(@TempDir Path dir) throws IOException {
    write(dir, "only.yaml", """
        tools:
          query_one:
            source: missing
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);

    Map<String, String> env = Map.of("YAML_VALIDATE_MERGED", "false");
    YamlConfigLoader mergeLoader = new YamlConfigLoader(env);
    ToolsConfig config = mergeLoader.loadAll(dir.toString(), MergeOptions.fromEnv(env));

    assertTrue(config.tools().containsKey("query_one"));
  }

  @Test
  void validateMergedFalseAppliesToSingleFilePath(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("only.yaml");
    Files.writeString(file, """
        tools:
          query_one:
            source: missing
            description: d
            statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
        """);

    Map<String, String> env = Map.of("YAML_VALIDATE_MERGED", "false");
    YamlConfigLoader mergeLoader = new YamlConfigLoader(env);
    ToolsConfig config = mergeLoader.loadAll(file.toString(), MergeOptions.fromEnv(env));

    assertTrue(config.tools().containsKey("query_one"));
  }
}
