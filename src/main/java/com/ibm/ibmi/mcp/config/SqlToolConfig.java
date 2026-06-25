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
 * <p>MVP note: {@code responseFormat}, {@code tableFormat}, and {@code maxDisplayRows} are
 * parsed but only the default JSON response format is implemented.
 */
public record SqlToolConfig(
    String name,
    boolean enabled,
    String source,
    String description,
    String statement,
    List<ParameterConfig> parameters,
    String responseFormat,
    Map<String, Object> annotations,
    SecurityConfig security,
    Integer rowsToFetch,
    Boolean fetchAllRows,
    String domain,
    String category) {

  public static final int DEFAULT_ROWS_TO_FETCH = 100;
  public static final int DEFAULT_PAGE_SIZE = 1000;
  public static final int MAX_PAGINATION_ROWS = 30000;

  public boolean isFetchAll() {
    return fetchAllRows != null && fetchAllRows && rowsToFetch == null;
  }

  public int effectiveRowsToFetch() {
    return rowsToFetch != null ? rowsToFetch : DEFAULT_ROWS_TO_FETCH;
  }
}
