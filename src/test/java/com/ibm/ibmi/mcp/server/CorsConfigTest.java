package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class CorsConfigTest {

  @Test
  void resolve_emptyAndNull_nonProduction_allowsAll() {
    assertEquals(Set.of("*"), CorsConfig.resolveOriginPatterns(null, null));
    assertEquals(Set.of("*"), CorsConfig.resolveOriginPatterns("", "development"));
    assertEquals(Set.of("*"), CorsConfig.resolveOriginPatterns("  ", "test"));
  }

  @Test
  void resolve_empty_production_deniesAll() {
    assertEquals(Set.of(), CorsConfig.resolveOriginPatterns(null, "production"));
    assertEquals(Set.of(), CorsConfig.resolveOriginPatterns("", "production"));
    assertEquals(Set.of(), CorsConfig.resolveOriginPatterns("  ,  ", "production"));
  }

  @Test
  void resolve_production_isCaseInsensitiveAndTrimmed() {
    assertEquals(Set.of(), CorsConfig.resolveOriginPatterns(null, "PRODUCTION"));
    assertEquals(Set.of(), CorsConfig.resolveOriginPatterns(null, "Production"));
    assertEquals(Set.of(), CorsConfig.resolveOriginPatterns(null, "  production  "));
  }

  @Test
  void resolve_csvStar_allowsAllWithoutQuoting() {
    assertEquals(Set.of("*"), CorsConfig.resolveOriginPatterns("*", null));
    assertEquals(Set.of("*"), CorsConfig.resolveOriginPatterns(" * ", "production"));
  }

  @Test
  void resolve_csv_trimsAndDropsEmpties_quotesAsRegex() {
    Set<String> patterns = CorsConfig.resolveOriginPatterns(
        " http://localhost:5173 , , https://app.example.com ",
        "production");

    assertEquals(2, patterns.size());
    assertTrue(patterns.contains(Pattern.quote("http://localhost:5173")));
    assertTrue(patterns.contains(Pattern.quote("https://app.example.com")));
  }

  @Test
  void resolve_allowlistWinsOverProductionEnv() {
    Set<String> patterns = CorsConfig.resolveOriginPatterns(
        "http://localhost:5173", "production");
    assertEquals(Set.of(Pattern.quote("http://localhost:5173")), patterns);
  }

  @Test
  void apply_setsMethodsHeadersCredentials() {
    org.eclipse.jetty.server.handler.CrossOriginHandler cors =
        new org.eclipse.jetty.server.handler.CrossOriginHandler();
    CorsConfig.apply(cors, Set.of("*"));

    assertEquals(Set.of("*"), cors.getAllowedOriginPatterns());
    assertTrue(cors.isAllowCredentials());
    assertEquals(CorsConfig.ALLOWED_METHODS, cors.getAllowedMethods());
    assertEquals(CorsConfig.ALLOWED_HEADERS, cors.getAllowedHeaders());
    assertEquals(CorsConfig.EXPOSED_HEADERS, cors.getExposedHeaders());
  }

  @Test
  void resolve_csvStarAndOtherOrigins_throwsIllegalArgumentException() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> CorsConfig.resolveOriginPatterns(
            "*,https://example.com", "production"));
    assertTrue(ex.getMessage().contains("cannot be mixed"));
  }
}
