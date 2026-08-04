package com.ibm.ibmi.mcp.config;

import java.util.Map;

/**
 * A Mapepire connection definition from the {@code sources:} section of a tools YAML file.
 *
 * <p>{@code jdbcOptions} holds the merged result of the optional YAML {@code jdbc-options:}
 * block and the {@code DB2i_JDBC_OPTIONS} environment variable (env wins on key collisions).
 * {@code mcp-pool-idle-timeout-ms} / {@code mcp-pool-query-timeout-ms} control pool idle
 * eviction and Mapepire execute/fetch waits ({@code 0} disables each). When omitted,
 * {@code MCP_POOL_IDLE_TIMEOUT_MS} / {@code MCP_POOL_QUERY_TIMEOUT_MS} apply (Node
 * defaults 300000 / 30000); YAML wins when set.
 *
 * <pre>
 * sources:
 *   ibmi-system:
 *     host: ${DB2i_HOST}
 *     user: ${DB2i_USER}
 *     password: ${DB2i_PASS}
 *     port: 8076
 *     ignore-unauthorized: true
 *     max-size: 10
 *     starting-size: 2
 *     jdbc-options:
 *       libraries: [QSYS, QGPL]
 *       naming: system
 * </pre>
 */
public record SourceConfig(
    String name,
    String host,
    int port,
    String user,
    String password,
    boolean ignoreUnauthorized,
    int maxSize,
    int startingSize,
    int mcpPoolIdleTimeoutMs,
    int mcpPoolQueryTimeoutMs,
    Map<String, Object> jdbcOptions) {

  public static final int DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS = 300_000;
  public static final int DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS = 30_000;
  public static final int DEFAULT_MAPEPIRE_PORT = 8076;
  public static final int DEFAULT_MAX_SIZE = 10;
  public static final int DEFAULT_STARTING_SIZE = 2;
}
