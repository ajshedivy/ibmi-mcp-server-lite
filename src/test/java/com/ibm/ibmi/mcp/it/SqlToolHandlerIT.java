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
import com.ibm.ibmi.mcp.server.SqlToolHandler;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Live pipeline smoke: YamlConfigLoader → SourceManager → SqlToolHandler.apply,
 * mirroring scripts/smoke-test.py at the Java layer (not stdio JSON-RPC).
 */
class SqlToolHandlerIT {

  private static final Path SAMPLE_TOOLS = Path.of("tools/sample-tools.yaml");
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
  void systemStatusNoParamToolReturnsSuccess() {
    // system_status has parameters: [] — exercises the pool.query(sql) branch
    // (no QueryOptions) that parameterized tools never hit.
    CallToolResult result = call("system_status", Map.of());

    assertSuccessfulSqlOutput(result);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void activeJobInfoReturnsSuccessWithMetadata() {
    CallToolResult result = call("active_job_info", Map.of("limit", 3));

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

  private CallToolResult call(String toolName, Map<String, Object> arguments) {
    SqlToolConfig tool = config.tools().get(toolName);
    assertNotNull(tool, "tool not found in sample-tools.yaml: " + toolName);
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
