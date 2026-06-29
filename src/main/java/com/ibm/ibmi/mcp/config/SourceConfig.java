package com.ibm.ibmi.mcp.config;

import java.util.Map;

/**
 * A Mapepire connection definition from the {@code sources:} section of a tools YAML file.
 *
 * <p>{@code jdbcOptions} holds the merged result of the optional YAML {@code jdbc-options:}
 * block and the {@code DB2i_JDBC_OPTIONS} environment variable (env wins on key collisions).
 *
 * <pre>
 * sources:
 *   ibmi-system:
 *     host: ${DB2i_HOST}
 *     user: ${DB2i_USER}
 *     password: ${DB2i_PASS}
 *     port: 8076
 *     ignore-unauthorized: true
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
    Map<String, Object> jdbcOptions) {

  public static final int DEFAULT_MAPEPIRE_PORT = 8076;
}
