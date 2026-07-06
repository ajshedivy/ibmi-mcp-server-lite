package com.ibm.ibmi.mcp.server;

/**
 * HTTP transport bind settings for {@link McpServerRunner#startHttp}.
 */
public record TransportConfig(String httpHost, int httpPort, String httpEndpoint) {

  public static final String DEFAULT_HOST = "0.0.0.0";
  public static final int DEFAULT_PORT = 3010;
  public static final String DEFAULT_ENDPOINT = "/mcp";
}
