package com.ibm.ibmi.mcp.config;

import java.util.List;

/**
 * The optional {@code security:} block of a tool definition.
 *
 * <p>Defaults (applied by {@link com.ibm.ibmi.mcp.sql.SqlSecurityValidator}): {@code readOnly}
 * is treated as {@code true} even when this block is absent; {@code maxQueryLength} defaults
 * to 10000 characters.
 */
public record SecurityConfig(
    Boolean readOnly,
    Integer maxQueryLength,
    List<String> forbiddenKeywords) {

  public static final SecurityConfig DEFAULTS = new SecurityConfig(null, null, null);
}
