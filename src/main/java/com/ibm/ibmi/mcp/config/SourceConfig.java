package com.ibm.ibmi.mcp.config;

/**
 * A Mapepire connection definition from the {@code sources:} section of a tools YAML file.
 *
 * <pre>
 * sources:
 *   ibmi-system:
 *     host: ${DB2i_HOST}
 *     user: ${DB2i_USER}
 *     password: ${DB2i_PASS}
 *     port: 8076
 *     ignore-unauthorized: true
 * </pre>
 */
public record SourceConfig(
    String name,
    String host,
    int port,
    String user,
    String password,
    boolean ignoreUnauthorized) {

  public static final int DEFAULT_MAPEPIRE_PORT = 8076;
}
