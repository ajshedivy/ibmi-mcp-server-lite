package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.mapepire.SourceManager;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class SqlToolHandlerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void executeSqlRejectsNonSelectAtCallTime() {
    SqlToolConfig executeSql = BuiltinTools.executeSql("ibmi-system", true);
    SqlToolHandler handler = new SqlToolHandler(executeSql, new SourceManager(Map.of()), mapper);

    CallToolResult result = handler.apply(
        null,
        new CallToolRequest(
            BuiltinTools.EXECUTE_SQL_NAME,
            Map.of("sql", "DELETE FROM SAMPLE.EMPLOYEE"),
            null));

    assertTrue(result.isError());
    @SuppressWarnings("unchecked")
    Map<String, Object> output = (Map<String, Object>) result.structuredContent();
    assertFalse((Boolean) output.get("success"));
    String error = output.get("error").toString();
    assertTrue(error.contains("read-only") || error.contains("DELETE"),
        "expected read-only or DELETE in error message, got: " + error);
  }
}
