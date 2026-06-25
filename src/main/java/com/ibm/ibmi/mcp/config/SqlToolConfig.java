package com.ibm.ibmi.mcp.config;

import java.util.List;
import java.util.Map;

/**
 * A SQL tool definition from the {@code tools:} section of a tools YAML file.
 *
 * <p>{@code fetchAllRows: true} enables automatic pagination up to {@link #MAX_PAGINATION_ROWS}
 * rows using {@link #DEFAULT_PAGE_SIZE} per page. This setting is ignored if {@code rowsToFetch}
 * is explicitly set (use {@link #isFetchAll()} to check the effective behavior).
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
    String category) {

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

  public String effectiveTableFormat() {
    return tableFormat != null ? tableFormat : DEFAULT_TABLE_FORMAT;
  }

  public int effectiveMaxDisplayRows() {
    return maxDisplayRows != null ? maxDisplayRows : DEFAULT_MAX_DISPLAY_ROWS;
  }
}
