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
 * <p>{@code ignoreUnauthorized} is the YAML {@code ignore-unauthorized} flag (Node
 * schema name). {@code true} skips Mapepire TLS certificate-chain and hostname
 * verification. When the YAML key is omitted, {@link #ENV_IGNORE_UNAUTHORIZED}
 * applies (default {@code false}). YAML wins when the key is present.
 *
 * <pre>
 * sources:
 *   ibmi-system:
 *     host: ${DB2i_HOST}
 *     user: ${DB2i_USER}
 *     password: ${DB2i_PASS}
 *     port: 8076
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

  /** Env fallback when YAML omits {@code ignore-unauthorized}. Default is verify TLS. */
  public static final String ENV_IGNORE_UNAUTHORIZED = "DB2i_IGNORE_UNAUTHORIZED";
}
