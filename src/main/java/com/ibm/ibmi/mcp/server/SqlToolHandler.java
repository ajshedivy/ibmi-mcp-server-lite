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
import com.ibm.ibmi.mcp.mapepire.MapepireFailures;
import com.ibm.ibmi.mcp.format.SqlMarkdownFormatter;
import com.ibm.ibmi.mcp.mapepire.SourceManager;
import com.ibm.ibmi.mcp.sql.BoundStatement;
import com.ibm.ibmi.mcp.sql.ParameterProcessor;
import com.ibm.ibmi.mcp.sql.SqlSecurityValidator;

import io.github.mapepire_ibmi.Pool;
import io.github.mapepire_ibmi.Query;
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
 * always returned as MCP {@code structuredContent}, with a companion text block that is
 * either pretty-printed JSON (default) or markdown when {@code responseFormat: markdown}
 * is configured on the tool.
 *
 * <p>When {@code fetchAllRows: true}, pagination automatically fetches up to
 * {@link SqlToolConfig#MAX_PAGINATION_ROWS} rows using {@link SqlToolConfig#DEFAULT_PAGE_SIZE}
 * per page. The {@code truncated} metadata flag indicates if results were capped.
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

      PaginatedResult paginated = executeQuery(bound);

      long elapsed = System.currentTimeMillis() - start;
      Map<String, Object> output = buildOutput(
          paginated, elapsed, bound.parameters().size(), bound.sql(), request.arguments());
      return CallToolResult.builder()
          .addTextContent(formatTextContent(output))
          .structuredContent(output)
          .isError(false)
          .build();
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (MapepireFailures.isConnectionLevel(e)) {
        sources.evictPool(tool.source());
      }
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

  /**
   * Executes the query with pagination support for {@code fetchAllRows: true} tools.
   *
   * @return a {@link PaginatedResult} containing rows, metadata, and truncation status
   */
  private PaginatedResult executeQuery(BoundStatement bound) throws Exception {
    Pool pool = sources.getPool(tool.source());
    Query query = bound.parameters().isEmpty()
        ? pool.query(bound.sql())
        : pool.query(bound.sql(), new QueryOptions(false, false, bound.parameters()));
    
        ? job.query(bound.sql())
        : job.query(bound.sql(), new QueryOptions(false, false, bound.parameters()));

    try {
      if (tool.isFetchAll()) {
        return executePaginatedQuery(query);
      } else {
        QueryResult<Object> result = query.<Object>execute(tool.effectiveRowsToFetch()).get();
        return new PaginatedResult(result, false);
      }
    } finally {
      try {
        query.close().get();
      } catch (Exception e) {
        log.warn("Failed to close query for tool '{}': {}", tool.name(), e.getMessage());
      }
    }
  }

  /**
   * Fetches all rows up to {@link SqlToolConfig#MAX_PAGINATION_ROWS} using pagination.
   *
   * @param query the Mapepire query to paginate
   * @return accumulated result with truncation flag
   */
  private PaginatedResult executePaginatedQuery(Query query) throws Exception {
    // Fetch first page - preserve this for column metadata
    QueryResult<Object> firstResult = query.<Object>execute(SqlToolConfig.DEFAULT_PAGE_SIZE).get();
    QueryResult<Object> lastResult = firstResult;
    List<Object> accumulated = new ArrayList<>(firstResult.getData() != null ? firstResult.getData() : List.of());

    // Paginate while more data exists and under the limit
    while (!lastResult.getIsDone() && accumulated.size() < SqlToolConfig.MAX_PAGINATION_ROWS) {
      lastResult = query.<Object>fetchMore(SqlToolConfig.DEFAULT_PAGE_SIZE).get();
      if (lastResult.getData() != null) {
        accumulated.addAll(lastResult.getData());
      }
    }

    // Determine if results were truncated
    boolean truncated = !lastResult.getIsDone() || accumulated.size() > SqlToolConfig.MAX_PAGINATION_ROWS;

    // Hard-clip to MAX_PAGINATION_ROWS
    if (accumulated.size() > SqlToolConfig.MAX_PAGINATION_ROWS) {
      accumulated = accumulated.subList(0, SqlToolConfig.MAX_PAGINATION_ROWS);
    }

    return new PaginatedResult(firstResult, accumulated, truncated);
  }

  static String formatToolResult(SqlToolConfig tool, Map<String, Object> output, ObjectMapper mapper)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    SqlToolHandler handler = new SqlToolHandler(tool, null, mapper);
    return handler.formatTextContent(output);
  }

  private String formatTextContent(Map<String, Object> output)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if ("markdown".equals(tool.responseFormat())) {
      return SqlMarkdownFormatter.format(
          output, tool.effectiveTableFormat(), tool.effectiveMaxDisplayRows());
    }
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
  }

  /**
   * Wraps a query result with pagination metadata.
   *
   * @param result the last query result (for metadata)
   * @param accumulatedRows accumulated rows from all pages (null for non-paginated queries)
   * @param truncated true if results were capped at MAX_PAGINATION_ROWS or query wasn't fully consumed
   */
  private record PaginatedResult(
      QueryResult<Object> result,
      List<Object> accumulatedRows,
      boolean truncated) {

    // Constructor for non-paginated results
    PaginatedResult(QueryResult<Object> result, boolean truncated) {
      this(result, null, truncated);
    }
  }

  private Map<String, Object> buildOutput(
      PaginatedResult paginated,
      long elapsedMs,
      int paramCount,
      String sqlStatement,
      Map<String, Object> parameters) {
    QueryResult<Object> result = paginated.result();
    // Use accumulated rows if present (pagination case), otherwise use result data
    List<Object> rows = paginated.accumulatedRows() != null
        ? paginated.accumulatedRows()
        : (result.getData() == null ? List.of() : result.getData());

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
    metadata.put("sqlStatement", sqlStatement);
    metadata.put("parameters", parameters != null ? parameters : Map.of());
    if (result.getUpdateCount() >= 0) {
      metadata.put("affectedRows", result.getUpdateCount());
    }
    if (paginated.truncated()) {
      metadata.put("truncated", true);
      log.warn("Tool '{}' result truncated at {} rows (query returned more data than MAX_PAGINATION_ROWS)",
          tool.name(), SqlToolConfig.MAX_PAGINATION_ROWS);
    } else {
      metadata.put("truncated", false);
    }

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("success", true);
    output.put("data", rows);
    output.put("metadata", metadata);
    return output;
  }
}
