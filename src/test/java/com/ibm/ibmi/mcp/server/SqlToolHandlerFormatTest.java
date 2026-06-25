package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.SecurityConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;

class SqlToolHandlerFormatTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void markdownFormatReturnsMarkdownText() throws Exception {
    SqlToolConfig tool = toolWithFormat("markdown");
    Map<String, Object> output = sampleOutput();

    String text = SqlToolHandler.formatToolResult(tool, output, mapper);

    assertTrue(text.startsWith("## "));
    assertTrue(text.contains("[!TIP]"));
    assertFalse(text.trim().startsWith("{"));
  }

  @Test
  void jsonFormatReturnsPrettyPrintedJson() throws Exception {
    SqlToolConfig tool = toolWithFormat(null);
    Map<String, Object> output = sampleOutput();

    String text = SqlToolHandler.formatToolResult(tool, output, mapper);

    assertTrue(text.trim().startsWith("{"));
    assertTrue(text.contains("\"success\" : true"));
    assertTrue(text.contains("\"toolName\" : \"test_tool\""));
  }

  @Test
  void structuredContentShapeUnchangedRegardlessOfFormat() {
    Map<String, Object> output = sampleOutput();
    assertEquals(true, output.get("success"));
    assertTrue(output.containsKey("data"));
    assertTrue(output.containsKey("metadata"));

    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) output.get("metadata");
    assertEquals("test_tool", metadata.get("toolName"));
    assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1", metadata.get("sqlStatement"));
    assertEquals(Map.of("limit", 5), metadata.get("parameters"));
  }

  private static SqlToolConfig toolWithFormat(String responseFormat) {
    return new SqlToolConfig(
        "test_tool", true, "src", "desc", "SELECT 1 FROM SYSIBM.SYSDUMMY1",
        List.of(), responseFormat, null, null, Map.of(), SecurityConfig.DEFAULTS, null, null, null);
  }

  private static Map<String, Object> sampleOutput() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("toolName", "test_tool");
    metadata.put("rowCount", 1);
    metadata.put("executionTime", 25L);
    metadata.put("parameterCount", 1);
    metadata.put("sqlStatement", "SELECT 1 FROM SYSIBM.SYSDUMMY1");
    metadata.put("parameters", Map.of("limit", 5));
    metadata.put("columns", List.of(Map.of("name", "COL1", "type", "INTEGER", "label", "COL1")));

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("success", true);
    output.put("data", List.of(Map.of("COL1", 1)));
    output.put("metadata", metadata);
    return output;
  }
}
