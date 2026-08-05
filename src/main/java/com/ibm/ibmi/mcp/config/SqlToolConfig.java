package com.ibm.ibmi.mcp.config;

import java.util.List;
import java.util.Map;

/**
 * A SQL tool definition from the {@code tools:} section of a tools YAML file.
 *
 * <p>{@code fetchAllRows: true} enables automatic pagination up to {@link #MAX_PAGINATION_ROWS}
 * rows using {@link #DEFAULT_PAGE_SIZE} per page. This setting is ignored if {@code rowsToFetch}
 * is explicitly set (use {@link #isFetchAll()} to check the effective behavior).
 *
 * @param emptyResultError message returned as a tool failure when the query yields no rows,
 *     or {@code null} to treat an empty result set as a successful answer (the default, and
 *     the right choice for anything that filters). There is no YAML key for this; it exists
 *     for built-ins whose empty result means "not found" rather than "nothing matched".
 */
public record SqlToolConfig(
    String name,
    boolean enabled,
    String source,
    String description,
    String statement,
    List<ParameterConfig> parameters,
    String responseFormat,
    String tableFormat,
    Integer maxDisplayRows,
    Map<String, Object> annotations,
    SecurityConfig security,
    Integer rowsToFetch,
    Boolean fetchAllRows,
    String domain,
    String category,
    String emptyResultError) {

  public static final int DEFAULT_ROWS_TO_FETCH = 100;
  public static final int DEFAULT_PAGE_SIZE = 1000;
  public static final int MAX_PAGINATION_ROWS = 30000;
  public static final String DEFAULT_TABLE_FORMAT = "markdown";
  public static final int DEFAULT_MAX_DISPLAY_ROWS = 100;

  public boolean isFetchAll() {
    return fetchAllRows != null && fetchAllRows && rowsToFetch == null;
  }

  public int effectiveRowsToFetch() {
    return rowsToFetch != null ? rowsToFetch : DEFAULT_ROWS_TO_FETCH;
  }

  /** Resolved table style for markdown responses; defaults to {@link #DEFAULT_TABLE_FORMAT}. */
  public String effectiveTableFormat() {
    return tableFormat != null ? tableFormat : DEFAULT_TABLE_FORMAT;
  }

  /** Resolved row cap for markdown result tables; defaults to {@link #DEFAULT_MAX_DISPLAY_ROWS}. */
  public int effectiveMaxDisplayRows() {
    return maxDisplayRows != null ? maxDisplayRows : DEFAULT_MAX_DISPLAY_ROWS;
  }

  /**
   * Copy of this config that reports an empty result set as a failure carrying {@code message}.
   * Kept as a wither so the built-in factory helpers do not all have to thread a parameter that
   * only one tool sets.
   */
  public SqlToolConfig withEmptyResultError(String message) {
    return new SqlToolConfig(
        name,
        enabled,
        source,
        description,
        statement,
        parameters,
        responseFormat,
        tableFormat,
        maxDisplayRows,
        annotations,
        security,
        rowsToFetch,
        fetchAllRows,
        domain,
        category,
        message);
  }
}
