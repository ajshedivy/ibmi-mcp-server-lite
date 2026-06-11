package com.ibm.ibmi.mcp.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.SecurityConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.mapepire.SourceManager;
import com.ibm.ibmi.mcp.sql.BoundStatement;
import com.ibm.ibmi.mcp.sql.ParameterProcessor;
import com.ibm.ibmi.mcp.sql.SqlSecurityValidator;

import io.github.mapepire_ibmi.Query;
import io.github.mapepire_ibmi.SqlJob;
import io.github.mapepire_ibmi.types.ColumnMetadata;
import io.github.mapepire_ibmi.types.QueryOptions;
import io.github.mapepire_ibmi.types.QueryResult;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Executes one YAML-defined SQL tool: validates security, binds parameters, runs the
 * statement through Mapepire, and formats the result.
 *
 * <p>The result mirrors the reference server's {@code StandardSqlToolOutput} shape —
 * {@code {success, data, metadata:{toolName, rowCount, executionTime, columns, ...}}} —
 * returned both as a JSON text block and as MCP {@code structuredContent}.
 *
 * <p>INTERN TODO: {@code responseFormat: markdown} table rendering, fetchAllRows
 * pagination via {@code fetchMore()}, and registering an {@code outputSchema}.
 */
public final class SqlToolHandler
    implements BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> {

  private static final Logger log = LoggerFactory.getLogger(SqlToolHandler.class);

  private final SqlToolConfig tool;
  private final SourceManager sources;
  private final ObjectMapper mapper;

  public SqlToolHandler(SqlToolConfig tool, SourceManager sources, ObjectMapper mapper) {
    this.tool = tool;
    this.sources = sources;
    this.mapper = mapper;
  }

  @Override
  public CallToolResult apply(McpSyncServerExchange exchange, CallToolRequest request) {
    try {
      BoundStatement bound = ParameterProcessor.prepare(tool, request.arguments());
      if (tool.security() != SecurityConfig.DEFAULTS) {
        // Reference behavior: tools with an explicit security block are re-validated
        // against the processed SQL at execution time.
        SqlSecurityValidator.validate(bound.sql(), tool.security());
      }

      log.info("Executing tool '{}' ({} bound parameters)", tool.name(), bound.parameters().size());
      long start = System.currentTimeMillis();

      SqlJob job = sources.getJob(tool.source());
      Query query = bound.parameters().isEmpty()
          ? job.query(bound.sql())
          : job.query(bound.sql(), new QueryOptions(false, false, bound.parameters()));
      QueryResult<Object> result;
      try {
        result = query.<Object>execute(tool.effectiveRowsToFetch()).get();
      } finally {
        try {
          query.close().get();
        } catch (Exception e) {
          log.warn("Failed to close query for tool '{}': {}", tool.name(), e.getMessage());
        }
      }

      long elapsed = System.currentTimeMillis() - start;
      Map<String, Object> output = buildOutput(result, elapsed, bound.parameters().size());
      return CallToolResult.builder()
          .addTextContent(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output))
          .structuredContent(output)
          .isError(false)
          .build();
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      log.error("Tool '{}' failed: {}", tool.name(), cause.getMessage());
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("success", false);
      output.put("data", List.of());
      output.put("error", cause.getMessage());
      return CallToolResult.builder()
          .addTextContent("Error executing '" + tool.name() + "': " + cause.getMessage())
          .structuredContent(output)
          .isError(true)
          .build();
    }
  }

  private Map<String, Object> buildOutput(QueryResult<Object> result, long elapsedMs, int paramCount) {
    List<Object> rows = result.getData() == null ? List.of() : result.getData();

    List<Map<String, Object>> columns = new ArrayList<>();
    if (result.getMetadata() != null && result.getMetadata().getColumns() != null) {
      for (ColumnMetadata col : result.getMetadata().getColumns()) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", col.getName());
        c.put("type", col.getType());
        c.put("label", col.getLabel());
        columns.add(c);
      }
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("toolName", tool.name());
    metadata.put("rowCount", rows.size());
    metadata.put("executionTime", elapsedMs);
    metadata.put("columns", columns);
    metadata.put("parameterMode", tool.parameters().isEmpty() ? "none" : "parameters");
    metadata.put("parameterCount", paramCount);
    if (result.getUpdateCount() >= 0) {
      metadata.put("affectedRows", result.getUpdateCount());
    }

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("success", true);
    output.put("data", rows);
    output.put("metadata", metadata);
    return output;
  }
}
