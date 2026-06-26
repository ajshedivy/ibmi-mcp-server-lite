package com.ibm.ibmi.mcp.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SqlToolConfig}, focusing on the {@code fetchAllRows} precedence logic.
 */
class SqlToolConfigTest {

  @Test
  void isFetchAll_whenFetchAllRowsTrueAndNoRowsToFetch_returnsTrue() {
    SqlToolConfig tool = new SqlToolConfig(
        "test",
        true,
        "source",
        "description",
        "SELECT 1",
        List.of(),
        null,
        null,
        null,
        Map.of(),
        SecurityConfig.DEFAULTS,
        null,           // rowsToFetch = null
        true,           // fetchAllRows = true
        null,
        null
    );
    
    assertTrue(tool.isFetchAll(), "fetchAllRows=true with no rowsToFetch should enable fetch-all");
  }

  @Test
  void isFetchAll_whenFetchAllRowsFalse_returnsFalse() {
    SqlToolConfig tool = new SqlToolConfig(
        "test",
        true,
        "source",
        "description",
        "SELECT 1",
        List.of(),
        null,
        null,
        null,
        Map.of(),
        SecurityConfig.DEFAULTS,
        null,           // rowsToFetch = null
        false,          // fetchAllRows = false
        null,
        null
    );
    
    assertFalse(tool.isFetchAll(), "fetchAllRows=false should disable fetch-all");
  }

  @Test
  void isFetchAll_whenFetchAllRowsNull_returnsFalse() {
    SqlToolConfig tool = new SqlToolConfig(
        "test",
        true,
        "source",
        "description",
        "SELECT 1",
        List.of(),
        null,
        null,
        null,
        Map.of(),
        SecurityConfig.DEFAULTS,
        null,           // rowsToFetch = null
        null,           // fetchAllRows = null (not specified)
        null,
        null
    );
    
    assertFalse(tool.isFetchAll(), "fetchAllRows=null should disable fetch-all");
  }

  @Test
  void isFetchAll_whenBothFetchAllRowsAndRowsToFetchSet_returnsFalse() {
    // Precedence rule: rowsToFetch takes priority over fetchAllRows
    SqlToolConfig tool = new SqlToolConfig(
        "test",
        true,
        "source",
        "description",
        "SELECT 1",
        List.of(),
        null,
        null,
        null,
        Map.of(),
        SecurityConfig.DEFAULTS,
        100,            // rowsToFetch = 100 (explicit limit)
        true,           // fetchAllRows = true (ignored)
        null,
        null
    );
    
    assertFalse(tool.isFetchAll(), 
        "rowsToFetch takes precedence over fetchAllRows - should disable fetch-all");
  }

  @Test
  void isFetchAll_whenRowsToFetchSetAndFetchAllRowsFalse_returnsFalse() {
    SqlToolConfig tool = new SqlToolConfig(
        "test",
        true,
        "source",
        "description",
        "SELECT 1",
        List.of(),
        null,
        null,
        null,
        Map.of(),
        SecurityConfig.DEFAULTS,
        50,             // rowsToFetch = 50
        false,          // fetchAllRows = false
        null,
        null
    );
    
    assertFalse(tool.isFetchAll(), "Both disabled should return false");
  }

  @Test
  void isFetchAll_whenRowsToFetchSetAndFetchAllRowsNull_returnsFalse() {
    SqlToolConfig tool = new SqlToolConfig(
        "test",
        true,
        "source",
        "description",
        "SELECT 1",
        List.of(),
        null,
        null,
        null,
        Map.of(),
        SecurityConfig.DEFAULTS,
        200,            // rowsToFetch = 200
        null,           // fetchAllRows = null
        null,
        null
    );
    
    assertFalse(tool.isFetchAll(), "rowsToFetch set should disable fetch-all regardless of fetchAllRows");
  }
}
