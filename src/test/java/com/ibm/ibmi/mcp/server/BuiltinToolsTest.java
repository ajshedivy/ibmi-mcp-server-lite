package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.config.ParameterConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;

class BuiltinToolsTest {

  @Test
  void activeBuiltinNames_alwaysIncludesDescribeSqlObject() {
    assertEquals(
        Set.of(BuiltinTools.DESCRIBE_SQL_OBJECT_NAME),
        BuiltinTools.activeBuiltinNames(false, false));
  }

  @Test
  void activeBuiltinNames_includesDiscoveryWhenBuiltinToolsEnabled() {
    Set<String> names = BuiltinTools.activeBuiltinNames(true, false);
    assertTrue(names.contains(BuiltinTools.DESCRIBE_SQL_OBJECT_NAME));
    assertTrue(names.contains(BuiltinTools.LIST_SCHEMAS_NAME));
    assertTrue(names.contains(BuiltinTools.LIST_TABLES_IN_SCHEMA_NAME));
    assertTrue(names.contains(BuiltinTools.GET_TABLE_COLUMNS_NAME));
    assertTrue(names.contains(BuiltinTools.GET_RELATED_OBJECTS_NAME));
    assertTrue(names.contains(BuiltinTools.VALIDATE_QUERY_NAME));
    assertFalse(names.contains(BuiltinTools.EXECUTE_SQL_NAME));
  }

  @Test
  void activeBuiltinNames_includesExecuteSqlWhenEnabled() {
    assertEquals(
        Set.of(BuiltinTools.DESCRIBE_SQL_OBJECT_NAME, BuiltinTools.EXECUTE_SQL_NAME),
        BuiltinTools.activeBuiltinNames(false, true));
  }

  @Test
  void configsForGates_defaultOnlyDescribeSqlObject() {
    List<SqlToolConfig> configs = BuiltinTools.configsForGates("ibmi-system", false, false, true);
    assertEquals(1, configs.size());
    assertEquals(BuiltinTools.DESCRIBE_SQL_OBJECT_NAME, configs.get(0).name());
    assertEquals("ibmi-system", configs.get(0).source());
    assertEquals(Boolean.FALSE, configs.get(0).security().readOnly());
  }

  @Test
  void configsForGates_builtinToolsAddsDiscoverySet() {
    List<SqlToolConfig> configs = BuiltinTools.configsForGates("ibmi-system", true, false, true);
    assertEquals(6, configs.size());
    assertEquals(
        List.of(
            BuiltinTools.DESCRIBE_SQL_OBJECT_NAME,
            BuiltinTools.LIST_SCHEMAS_NAME,
            BuiltinTools.LIST_TABLES_IN_SCHEMA_NAME,
            BuiltinTools.GET_TABLE_COLUMNS_NAME,
            BuiltinTools.GET_RELATED_OBJECTS_NAME,
            BuiltinTools.VALIDATE_QUERY_NAME),
        configs.stream().map(SqlToolConfig::name).toList());
  }

  @Test
  void configsForGates_bothFlagsAddsFullChain() {
    List<SqlToolConfig> configs = BuiltinTools.configsForGates("ibmi-system", true, true, true);
    assertEquals(7, configs.size());
    assertEquals(BuiltinTools.EXECUTE_SQL_NAME, configs.get(6).name());
  }

  @Test
  void describeSqlObject_usesGenerateSqlCall() {
    SqlToolConfig tool = BuiltinTools.describeSqlObject("ibmi-system");
    assertTrue(tool.statement().contains("QSYS2.GENERATE_SQL"));
    assertTrue(tool.statement().contains(":object_name"));
    assertEquals(3, tool.parameters().size());
  }

  @Test
  void validateQuery_usesParseStatement() {
    SqlToolConfig tool = BuiltinTools.validateQuery("ibmi-system");
    assertTrue(tool.statement().contains("QSYS2.PARSE_STATEMENT"));
    assertTrue(Boolean.TRUE.equals(tool.security().readOnly()));
  }

  @Test
  void describeSqlObject_advertisesReadOnlyHintDespiteValidatorExemption() {
    SqlToolConfig tool = BuiltinTools.describeSqlObject("ibmi-system");
    // security.readOnly is the internal switch that lets the CALL past the validator;
    // readOnlyHint is what MCP clients see, and GENERATE_SQL writes nothing.
    assertEquals(Boolean.FALSE, tool.security().readOnly());
    assertEquals(Boolean.TRUE, tool.annotations().get("readOnlyHint"));
  }

  @Test
  void describeSqlObject_treatsAnEmptyResultAsAMiss() {
    assertEquals(
        BuiltinTools.NO_DDL_GENERATED,
        BuiltinTools.describeSqlObject("ibmi-system").emptyResultError());
  }

  @Test
  void filteringTools_treatAnEmptyResultAsAValidAnswer() {
    // Nothing matching a filter is a real answer; only a GENERATE_SQL miss is a failure.
    for (SqlToolConfig tool : List.of(
        BuiltinTools.listSchemas("ibmi-system"),
        BuiltinTools.listTablesInSchema("ibmi-system"),
        BuiltinTools.getTableColumns("ibmi-system"),
        BuiltinTools.getRelatedObjects("ibmi-system"),
        BuiltinTools.validateQuery("ibmi-system"))) {
      assertNull(tool.emptyResultError(), tool.name() + " should allow empty results");
    }
  }

  @Test
  void unboundedTools_fetchAllRowsSoResultsAreNotSilentlyClipped() {
    // No SQL-level row cap on these three, so the default 100-row single fetch would cut
    // DDL mid-statement and hide columns on wide tables.
    for (SqlToolConfig tool : List.of(
        BuiltinTools.describeSqlObject("ibmi-system"),
        BuiltinTools.getTableColumns("ibmi-system"),
        BuiltinTools.getRelatedObjects("ibmi-system"))) {
      assertTrue(tool.isFetchAll(), tool.name() + " should page the full result set");
    }
  }

  @Test
  void paginatingTools_fetchAsManyRowsAsTheLimitParameterAllows() {
    for (SqlToolConfig tool : List.of(
        BuiltinTools.listSchemas("ibmi-system"),
        BuiltinTools.listTablesInSchema("ibmi-system"))) {
      ParameterConfig limit = tool.parameters().stream()
          .filter(p -> "limit".equals(p.name()))
          .findFirst()
          .orElseThrow(() -> new AssertionError(tool.name() + " has no limit parameter"));
      assertEquals(limit.max().intValue(), tool.effectiveRowsToFetch(),
          tool.name() + " must fetch as many rows as its limit parameter permits");
      assertFalse(tool.isFetchAll(), tool.name() + " pages in SQL, not via fetch-all");
    }
  }

  @Test
  void listSchemas_supportsOptionalFilters() {
    SqlToolConfig tool = BuiltinTools.listSchemas("ibmi-system");
    assertTrue(tool.statement().contains(":include_system"));
    assertTrue(tool.statement().contains(":filter"));
    assertEquals("*ALL", tool.parameters().get(0).defaultValue());
  }
}
