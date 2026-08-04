package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
    assertFalse(Boolean.TRUE.equals(configs.get(0).security().readOnly()));
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
  void listSchemas_supportsOptionalFilters() {
    SqlToolConfig tool = BuiltinTools.listSchemas("ibmi-system");
    assertTrue(tool.statement().contains(":include_system"));
    assertTrue(tool.statement().contains(":filter"));
    assertEquals("*ALL", tool.parameters().get(0).defaultValue());
  }
}
