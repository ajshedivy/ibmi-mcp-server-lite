package com.ibm.ibmi.mcp.server;

import java.util.List;
import java.util.Map;

import com.ibm.ibmi.mcp.config.ParameterConfig;
import com.ibm.ibmi.mcp.config.SecurityConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;

/** Programmatically defined MCP tools (not loaded from YAML). */
public final class BuiltinTools {

  public static final String EXECUTE_SQL_NAME = "execute_sql";

  private static final String EXECUTE_SQL_DESCRIPTION =
      "Executes a SELECT query on the IBM i database and returns the results.";

  private BuiltinTools() {}

  /**
   * Built-in ad-hoc SQL tool. The statement {@code :sql} uses direct substitution at call time;
   * {@link com.ibm.ibmi.mcp.sql.SqlSecurityValidator} enforces read-only when configured.
   */
  public static SqlToolConfig executeSql(String sourceName, boolean readOnly) {
    return new SqlToolConfig(
        EXECUTE_SQL_NAME,
        true,
        sourceName,
        EXECUTE_SQL_DESCRIPTION,
        ":sql",
        List.of(new ParameterConfig(
            "sql",
            "string",
            "The SQL query to execute on the IBM i database",
            null,
            true,
            null, null, null, null, null, null, null)),
        null,
        null,
        null,
        Map.of("readOnlyHint", readOnly),
        new SecurityConfig(readOnly, 10_000, null),
        null,
        null,
        null,
        null);
  }
}
