package com.ibm.ibmi.mcp.server;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.handler.CrossOriginHandler;

/**
 * Resolves CORS origin patterns for {@link CrossOriginHandler} from
 * {@code MCP_ALLOWED_ORIGINS} and {@code MCP_SERVER_ENV}.
 *
 * <p>Semantics match the reference Node server:
 * <ul>
 *   <li>non-empty allowlist → exact origins (quoted as regex for the handler)</li>
 *   <li>sole {@code *} → allow any Origin (Jetty allow-any token)</li>
 *   <li>{@code *} mixed with other origins → rejected ({@link IllegalArgumentException})</li>
 *   <li>empty + {@code MCP_SERVER_ENV=production} → deny all</li>
 *   <li>empty + non-production → {@code *} (allow any)</li>
 * </ul>
 */
public final class CorsConfig {

  static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "DELETE", "OPTIONS");

  static final Set<String> ALLOWED_HEADERS = Set.of(
      "Content-Type",
      "Mcp-Session-Id",
      "Last-Event-ID",
      "Authorization");

  static final Set<String> EXPOSED_HEADERS = Set.of("Mcp-Session-Id");

  private CorsConfig() {}

  /**
   * Patterns for {@link CrossOriginHandler#setAllowedOriginPatterns(Set)}.
   *
   * @param allowedOriginsCsv comma-separated origins from {@code MCP_ALLOWED_ORIGINS}
   *     (may be {@code null} or blank)
   * @param serverEnv value of {@code MCP_SERVER_ENV} (may be {@code null})
   * @throws IllegalArgumentException if {@code *} appears alongside other origins
   */
  public static Set<String> resolveOriginPatterns(String allowedOriginsCsv, String serverEnv) {
    List<String> origins = parseCsv(allowedOriginsCsv);
    if (!origins.isEmpty()) {
      // Bare "*" is Jetty's allow-any token; do not Pattern.quote it.
      if (origins.contains("*")) {
        if (origins.size() == 1) {
          return Set.of("*");
        }
        throw new IllegalArgumentException(
            "MCP_ALLOWED_ORIGINS: '*' cannot be mixed with other origins; "
                + "use only '*' or a list of specific origins");
      }
      // CrossOriginHandler treats non-"*" entries as regex — quote for exact match.
      return origins.stream()
          .map(Pattern::quote)
          .collect(Collectors.toUnmodifiableSet());
    }
    String env = serverEnv == null ? "" : serverEnv.trim();
    if ("production".equalsIgnoreCase(env)) {
      return Set.of();
    }
    return Set.of("*");
  }

  /**
   * Configures a {@link CrossOriginHandler} with MCP-compatible methods, headers,
   * credentials, and the given origin patterns.
   */
  public static void apply(CrossOriginHandler cors, Set<String> originPatterns) {
    cors.setAllowedOriginPatterns(originPatterns);
    cors.setAllowCredentials(true);
    cors.setAllowedMethods(ALLOWED_METHODS);
    cors.setAllowedHeaders(ALLOWED_HEADERS);
    cors.setExposedHeaders(EXPOSED_HEADERS);
  }

  private static List<String> parseCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
