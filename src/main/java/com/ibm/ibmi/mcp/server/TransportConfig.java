package com.ibm.ibmi.mcp.server;

import java.util.Set;

/**
 * HTTP transport bind settings for {@link McpServerRunner#startHttp}.
 *
 * @param corsOriginPatterns patterns for Jetty {@code CrossOriginHandler}
 *     (use {@link CorsConfig#resolveOriginPatterns} from env)
 */
public record TransportConfig(
    String httpHost, int httpPort, String httpEndpoint, Set<String> corsOriginPatterns) {

  public static final String DEFAULT_HOST = "0.0.0.0";
  public static final int DEFAULT_PORT = 3010;
  public static final String DEFAULT_ENDPOINT = "/mcp";

  /**
   * Null patterns become deny-all ({@code Set.of()}) — fail-closed for misconfig.
   * Callers that want non-prod allow-any should pass {@code Set.of("*")}.
   */
  public TransportConfig {
    corsOriginPatterns = corsOriginPatterns == null
        ? Set.of()
        : Set.copyOf(corsOriginPatterns);
  }

  /** Convenience ctor with non-prod allow-all CORS ({@code *}). */
  public TransportConfig(String httpHost, int httpPort, String httpEndpoint) {
    this(httpHost, httpPort, httpEndpoint, Set.of("*"));
  }
}
