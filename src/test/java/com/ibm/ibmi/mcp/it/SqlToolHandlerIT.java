package com.ibm.ibmi.mcp.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;
import com.ibm.ibmi.mcp.mapepire.SourceManager;
import com.ibm.ibmi.mcp.server.BuiltinTools;
import com.ibm.ibmi.mcp.server.SqlToolHandler;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Live pipeline smoke: YamlConfigLoader → SourceManager → SqlToolHandler.apply,
 * mirroring scripts/smoke-test.py at the Java layer (not stdio JSON-RPC).
 */
class SqlToolHandlerIT {

  private static final Path SAMPLE_TOOLS = Path.of("tools/sample/sample-tools.yaml");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Catalog view whose generated DDL is comfortably longer than
   * {@link SqlToolConfig#DEFAULT_ROWS_TO_FETCH} lines, so {@code describe_sql_object} has to
   * page to return it whole. It is also the view {@code get_table_columns} reads, so it is
   * present wherever the built-ins are usable at all.
   */
  private static final String DDL_PROBE_OBJECT = "SYSCOLUMNS2";

  private SourceManager sources;
  private ToolsConfig config;

  @BeforeEach
  void setUp() {
    MapepireEnv.assumeAvailable();
    config = new YamlConfigLoader(MapepireEnv.environment()).load(SAMPLE_TOOLS);
    sources = new SourceManager(config.sources());
  }

  @AfterEach
  void tearDown() {
    if (sources != null) {
      sources.close();
      sources = null;
    }
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void fetchAllLibrariesNoParamToolReturnsSuccess() {
    // fetch_all_libraries has parameters: [] — exercises the pool.query(sql) branch
    // (no QueryOptions) that parameterized tools never hit. It also sets fetchAllRows,
    // so this is the only live coverage of the paginated fetch path.
    CallToolResult result = call("fetch_all_libraries", Map.of());

    assertSuccessfulSqlOutput(result);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void listUserLibrariesBindsNamedParamAndReturnsRows() {
    CallToolResult result = call(
        "list_user_libraries", Map.of("library_pattern", "QSYS2%"));

    assertFalse(result.isError());
    Map<String, Object> output = structured(result);
    assertEquals(Boolean.TRUE, output.get("success"));

    @SuppressWarnings("unchecked")
    List<Object> data = (List<Object>) output.get("data");
    assertNotNull(data);
    assertFalse(data.isEmpty(), "expected rows for library_pattern QSYS2%");

    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) output.get("metadata");
    assertNotNull(metadata);
    assertNotNull(metadata.get("rowCount"));
    assertNotNull(metadata.get("executionTime"));

    assertTextBlockParses(result);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void describeSqlObjectReturnsGeneratedDdlSourceLines() {
    // describe_sql_object is the only always-on builtin and the only tool that runs a CALL
    // returning a result set, so it needs live coverage that the YAML tools do not give it.
    CallToolResult result = callDescribeSqlObject(DDL_PROBE_OBJECT, "VIEW");

    assertFalse(result.isError(),
        "describe_sql_object failed against live Mapepire: " + firstTextBlock(result));
    Map<String, Object> output = structured(result);
    assertEquals(Boolean.TRUE, output.get("success"));

    @SuppressWarnings("unchecked")
    List<Object> data = (List<Object>) output.get("data");
    assertNotNull(data);
    assertTrue(
        data.stream().anyMatch(row -> row instanceof Map<?, ?> m && m.containsKey("SRCDTA")),
        "expected SRCDTA source lines in GENERATE_SQL output, got: " + data);

    // The probe object's DDL runs past the default single-fetch cap, so a run without
    // fetchAllRows would stop at DEFAULT_ROWS_TO_FETCH lines and cut the CREATE mid-statement.
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) output.get("metadata");
    assertEquals(Boolean.FALSE, metadata.get("truncated"), "DDL should not be truncated");
    assertTrue(data.size() > SqlToolConfig.DEFAULT_ROWS_TO_FETCH,
        "QSYS2/" + DDL_PROBE_OBJECT + " DDL was only " + data.size() + " lines, so this test no "
            + "longer proves the row cap is gone — pick a database object with longer DDL");
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void describeSqlObjectMissingObjectIsAnErrorNotAnEmptySuccess() {
    // GENERATE_SQL returns an empty result set instead of raising, so without the
    // emptyResultError wiring this would come back as success with data: [].
    CallToolResult result = callDescribeSqlObject("NO_SUCH_OBJECT_XYZ", "TABLE");

    assertTrue(result.isError(), "a missing object should be reported as a failure");
    String text = firstTextBlock(result);
    assertTrue(text.contains(BuiltinTools.NO_DDL_GENERATED),
        "expected the missing-object message, got: " + text);
    // The message has to name what was searched for, since object_library defaults to QSYS2
    // and looking in the wrong library is the mistake this error exists to explain.
    assertTrue(text.contains("NO_SUCH_OBJECT_XYZ") && text.contains("QSYS2"),
        "expected the searched object and library in the message, got: " + text);

    Map<String, Object> output = structured(result);
    assertEquals(Boolean.FALSE, output.get("success"));
    assertEquals(List.of(), output.get("data"));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void listUserLibrariesMissingRequiredArgIsError() {
    CallToolResult result = call("list_user_libraries", Map.of());

    assertTrue(result.isError());
    // Pin the failure to parameter validation: any exception (connection refused,
    // TLS mismatch, ...) also yields isError()==true, which would mask a broken env.
    String text = firstTextBlock(result);
    assertTrue(text.contains("Missing required parameter: library_pattern"),
        "expected missing-parameter validation error, got: " + text);
  }

  /**
   * Built directly rather than from YAML, since {@code describe_sql_object} is a built-in.
   * The source is picked the same way the server picks it when registering built-ins.
   */
  private CallToolResult callDescribeSqlObject(String objectName, String objectType) {
    SqlToolConfig tool =
        BuiltinTools.describeSqlObject(config.sources().keySet().iterator().next());
    return new SqlToolHandler(tool, sources, MAPPER).apply(
        null,
        new CallToolRequest(
            tool.name(),
            Map.of(
                "object_library", "QSYS2",
                "object_name", objectName,
                "object_type", objectType),
            null));
  }

  private CallToolResult call(String toolName, Map<String, Object> arguments) {
    SqlToolConfig tool = config.tools().get(toolName);
    assertNotNull(tool, "tool not found in sample tools YAML: " + toolName);
    SqlToolHandler handler = new SqlToolHandler(tool, sources, MAPPER);
    return handler.apply(null, new CallToolRequest(toolName, arguments, null));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structured(CallToolResult result) {
    assertNotNull(result.structuredContent(), "structuredContent required");
    return (Map<String, Object>) result.structuredContent();
  }

  private static void assertSuccessfulSqlOutput(CallToolResult result) {
    assertFalse(result.isError());
    Map<String, Object> output = structured(result);
    assertEquals(Boolean.TRUE, output.get("success"));
    assertInstanceOf(List.class, output.get("data"));

    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) output.get("metadata");
    assertNotNull(metadata);
    assertNotNull(metadata.get("rowCount"), "metadata.rowCount required");
    assertNotNull(metadata.get("executionTime"), "metadata.executionTime required");

    assertTextBlockParses(result);
  }

  private static String firstTextBlock(CallToolResult result) {
    assertNotNull(result.content(), "content required");
    assertFalse(result.content().isEmpty(), "expected at least one content block");
    return assertInstanceOf(TextContent.class, result.content().get(0)).text();
  }

  /** The companion text block must be valid JSON with success==true (formatTextContent path). */
  private static void assertTextBlockParses(CallToolResult result) {
    String text = firstTextBlock(result);
    try {
      Map<String, Object> parsed = MAPPER.readValue(
          text, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
      assertEquals(Boolean.TRUE, parsed.get("success"), "text block success flag");
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError("text block is not valid JSON: " + e.getMessage(), e);
    }
  }
}
