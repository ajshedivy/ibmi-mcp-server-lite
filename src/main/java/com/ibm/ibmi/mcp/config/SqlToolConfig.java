package com.ibm.ibmi.mcp.config;

import java.util.List;
import java.util.Map;

/**
 * A SQL tool definition from the {@code tools:} section of a tools YAML file.
 *
 * <p>MVP note: {@code responseFormat}, {@code tableFormat}, {@code maxDisplayRows} and
 * {@code fetchAllRows} are parsed but only the default JSON response format is implemented.
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
    String domain,
    String category) {

  public static final int DEFAULT_ROWS_TO_FETCH = 100;

  public int effectiveRowsToFetch() {
    return rowsToFetch != null ? rowsToFetch : DEFAULT_ROWS_TO_FETCH;
  }
}
