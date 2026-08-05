package com.ibm.ibmi.mcp.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

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
 * per page. Otherwise a single fetch of {@link SqlToolConfig#effectiveRowsToFetch()} rows runs.
 * Either way the {@code truncated} metadata flag reports whether rows were left behind.
 *
 * <p>An empty result set is a successful answer unless the tool sets
 * {@link SqlToolConfig#emptyResultError()}, in which case it is reported as a tool failure.
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
    // Outside try: catch only runs after create() succeeds, so RequestContext is always available.
    RequestContext context = RequestContext.create(tool.name());
    try {
      BoundStatement bound = ParameterProcessor.prepare(tool, request.arguments());
      if (tool.security() != SecurityConfig.DEFAULTS
          || ParameterProcessor.isDirectSubstitution(tool)) {
        // Re-validate processed SQL at execution time for tools with an explicit security
        // block and for direct-substitution tools (statement exactly :param).
        SqlSecurityValidator.validate(bound.sql(), tool.security());
      }

      log.info("[{}] Executing tool '{}' ({} bound parameters)", context.requestId(), tool.name(), bound.parameters().size());

      PaginatedResult paginated = executeQuery(context, bound);

      if (tool.emptyResultError() != null && paginated.rows().isEmpty()) {
        String message = tool.emptyResultError() + " (" + describeArguments(request.arguments()) + ")";
        log.info("[{}] Tool '{}' found nothing: {}", context.requestId(), tool.name(), message);
        return errorResult(message);
      }

      long elapsed = System.currentTimeMillis() - context.startMillis();
      Map<String, Object> output = buildOutput(
          paginated, elapsed, bound.parameters().size(), bound.sql(), request.arguments(), context);
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
      log.error("[{}] Tool '{}' failed: {}", context.requestId(), tool.name(), cause.getMessage());
      return errorResult(cause.getMessage());
    }
  }

  /**
   * Failure response in the standard {@code {success, data, error}} shape. The empty-result
   * path builds this directly rather than throwing: an empty result is not an exception, and
   * routing it through the catch block would run the caller's arguments through
   * {@link MapepireFailures#isConnectionLevel}, whose substring matching would evict the
   * source pool for an object innocently named something like {@code CONNECTION_LOG}.
   */
  private CallToolResult errorResult(String message) {
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("success", false);
    output.put("data", List.of());
    output.put("error", message);
    return CallToolResult.builder()
        .addTextContent("Error executing '" + tool.name() + "': " + message)
        .structuredContent(output)
        .isError(true)
        .build();
  }

  /** Renders the call arguments so a "not found" message says what was actually searched for. */
  private static String describeArguments(Map<String, Object> arguments) {
    if (arguments == null || arguments.isEmpty()) {
      return "no arguments";
    }
    return arguments.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(", "));
  }

  /**
   * Executes the query with pagination support for {@code fetchAllRows: true} tools.
   *
   * @return a {@link PaginatedResult} containing rows, metadata, and truncation status
   */
  private PaginatedResult executeQuery(RequestContext context, BoundStatement bound) throws Exception {
    String sourceName = tool.source();
    sources.beginQuery(sourceName);
    try {
      Pool pool = sources.getPool(sourceName);
      Query query = bound.parameters().isEmpty()
          ? pool.query(bound.sql())
          : pool.query(bound.sql(), new QueryOptions(false, false, bound.parameters()));

      try {
        if (tool.isFetchAll()) {
          PaginatedResult result = executePaginatedQuery(pool, query);
          sources.recordActivity(sourceName);
          return result;
        } else {
          QueryResult<Object> result = sources.awaitQuery(
              sourceName, query.<Object>execute(tool.effectiveRowsToFetch()), pool);
          sources.recordActivity(sourceName);
          // Rows beyond the single-shot cap are discarded when the query closes; report that
          // so callers can tell a complete result from a clipped one.
          return new PaginatedResult(result, !result.getIsDone());
        }
      } finally {
        // Bound close the same way as execute — unbounded .get() can wedge after a
        // timed-out / dead WebSocket even though awaitQuery already ended the pool.
        // Pass the same pool instance so a close timeout cannot evict a rebuilt pool.
        try {
          sources.awaitQuery(sourceName, query.close(), pool);
        } catch (Exception e) {
          if (MapepireFailures.isConnectionLevel(e)) {
            sources.evictPoolIfSame(sourceName, pool);
          }
          log.warn("[{}] Failed to close query for tool '{}': {}", context.requestId(), tool.name(), e.getMessage());
        }
      }
    } finally {
      sources.endQuery(sourceName);
    }
  }

  /**
   * Fetches all rows up to {@link SqlToolConfig#MAX_PAGINATION_ROWS} using pagination.
   *
   * @param pool the pool that owns {@code query}
   * @param query the Mapepire query to paginate
   * @return accumulated result with truncation flag
   */
  private PaginatedResult executePaginatedQuery(Pool pool, Query query) throws Exception {
    String sourceName = tool.source();
    // Fetch first page - preserve this for column metadata
    QueryResult<Object> firstResult = sources.awaitQuery(
        sourceName, query.<Object>execute(SqlToolConfig.DEFAULT_PAGE_SIZE), pool);
    QueryResult<Object> lastResult = firstResult;
    List<Object> accumulated = new ArrayList<>(firstResult.getData() != null ? firstResult.getData() : List.of());

    // Paginate while more data exists and under the limit
    while (!lastResult.getIsDone() && accumulated.size() < SqlToolConfig.MAX_PAGINATION_ROWS) {
      lastResult = sources.awaitQuery(
          sourceName, query.<Object>fetchMore(SqlToolConfig.DEFAULT_PAGE_SIZE), pool);
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

    /** Accumulated pages when paginated, otherwise the single fetch's rows. */
    List<Object> rows() {
      if (accumulatedRows != null) {
        return accumulatedRows;
      }
      return result.getData() == null ? List.of() : result.getData();
    }
  }

  private Map<String, Object> buildOutput(
      PaginatedResult paginated,
      long elapsedMs,
      int paramCount,
      String sqlStatement,
      Map<String, Object> parameters,
      RequestContext context) {
    QueryResult<Object> result = paginated.result();
    List<Object> rows = paginated.rows();

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
      int cap = tool.isFetchAll()
          ? SqlToolConfig.MAX_PAGINATION_ROWS
          : tool.effectiveRowsToFetch();
      log.warn("[{}] Tool '{}' result truncated at {} rows (row cap {}); more rows were available",
          context.requestId(), tool.name(), rows.size(), cap);
    } else {
      metadata.put("truncated", false);
    }

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("success", true);
    output.put("data", rows);
    output.put("metadata", metadata);
    log.info("[{}] Tool '{}' completed: {} rows in {} ms", context.requestId(), tool.name(), rows.size(), elapsedMs);
    return output;
  }
}
