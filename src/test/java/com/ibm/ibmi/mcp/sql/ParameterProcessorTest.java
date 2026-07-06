package com.ibm.ibmi.mcp.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.config.ParameterConfig;
import com.ibm.ibmi.mcp.config.SecurityConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;

class ParameterProcessorTest {

  private static ParameterConfig param(String name, String type, Object dflt, Boolean required) {
    return new ParameterConfig(name, type, null, dflt, required, null, null, null, null, null, null, null);
  }

  private static SqlToolConfig tool(String statement, ParameterConfig... params) {
    return new SqlToolConfig("test_tool", true, "src", "desc", statement,
        List.of(params), null, null, null, Map.of(), SecurityConfig.DEFAULTS, null, null, null, null);
  }

  @Test
  void noParametersPassesStatementThrough() {
    BoundStatement bound = ParameterProcessor.prepare(tool("SELECT 1 FROM SYSIBM.SYSDUMMY1"), Map.of());
    assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1", bound.sql());
    assertTrue(bound.parameters().isEmpty());
  }

  @Test
  void namedParameterBecomesPositional() {
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT * FROM T WHERE C = :val", param("val", "string", null, true)),
        Map.of("val", "A00"));
    assertEquals("SELECT * FROM T WHERE C = ?", bound.sql());
    assertEquals(List.of("A00"), bound.parameters());
  }

  @Test
  void repeatedNamedParameterBindsPerOccurrence() {
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT * FROM T WHERE A = :v OR B = :v", param("v", "string", null, true)),
        Map.of("v", "x"));
    assertEquals("SELECT * FROM T WHERE A = ? OR B = ?", bound.sql());
    assertEquals(List.of("x", "x"), bound.parameters());
  }

  @Test
  void arrayExpandsToOneMarkerPerElement() {
    ParameterConfig ids = new ParameterConfig("ids", "array", null, null, true,
        "string", null, null, null, null, null, null);
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT * FROM T WHERE ID IN (:ids)", ids),
        Map.of("ids", List.of("A", "B", "C")));
    assertEquals("SELECT * FROM T WHERE ID IN (?, ?, ?)", bound.sql());
    assertEquals(List.of("A", "B", "C"), bound.parameters());
  }

  @Test
  void defaultAppliedWhenArgumentMissing() {
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT * FROM T FETCH FIRST :limit ROWS ONLY", param("limit", "integer", 10, null)),
        Map.of());
    assertEquals(List.of(10), bound.parameters());
  }

  @Test
  void missingRequiredParameterThrows() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterProcessor.prepare(
            tool("SELECT * FROM T WHERE C = :val", param("val", "string", null, true)), Map.of()));
    assertTrue(e.getMessage().contains("val"));
  }

  @Test
  void booleanBindsAsOneOrZero() {
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT * FROM T WHERE (:flag = 1 OR X IS NULL)", param("flag", "boolean", null, true)),
        Map.of("flag", true));
    assertEquals(List.of(1), bound.parameters());
  }

  @Test
  void integerCoercesNumericString() {
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT * FROM T FETCH FIRST :n ROWS ONLY", param("n", "integer", null, true)),
        Map.of("n", "25"));
    assertEquals(List.of(25), bound.parameters());
  }

  @Test
  void unknownPlaceholderLeftVerbatim() {
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT :unknown FROM T WHERE C = :val", param("val", "string", null, true)),
        Map.of("val", "x"));
    assertEquals("SELECT :unknown FROM T WHERE C = ?", bound.sql());
  }

  @Test
  void directSubstitutionReturnsSqlWithEmptyBindings() {
    String query = "SELECT 1 FROM SYSIBM.SYSDUMMY1";
    BoundStatement bound = ParameterProcessor.prepare(
        tool(":sql", param("sql", "string", null, true)),
        Map.of("sql", query));
    assertEquals(query, bound.sql());
    assertTrue(bound.parameters().isEmpty());
  }

  @Test
  void directSubstitutionMissingSqlThrows() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterProcessor.prepare(
            tool(":sql", param("sql", "string", null, true)), Map.of()));
    assertTrue(e.getMessage().contains("sql"));
    assertTrue(e.getMessage().contains("direct substitution"));
  }

  @Test
  void directSubstitutionNonStringSqlThrows() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterProcessor.prepare(
            tool(":sql", param("sql", "string", null, true)),
            Map.of("sql", 42)));
    assertTrue(e.getMessage().contains("sql"));
    assertTrue(e.getMessage().contains("direct substitution"));
  }

  @Test
  void singleParameterWithFullStatementUsesPositionalBinding() {
    BoundStatement bound = ParameterProcessor.prepare(
        tool("SELECT * FROM T WHERE id = :id", param("id", "string", null, true)),
        Map.of("id", "A00"));
    assertEquals("SELECT * FROM T WHERE id = ?", bound.sql());
    assertEquals(List.of("A00"), bound.parameters());
  }
}
