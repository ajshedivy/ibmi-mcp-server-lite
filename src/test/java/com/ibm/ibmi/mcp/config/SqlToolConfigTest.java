package com.ibm.ibmi.mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    SqlToolConfig tool = fetchConfig(null, true);

    assertTrue(tool.isFetchAll(), "fetchAllRows=true with no rowsToFetch should enable fetch-all");
  }

  @Test
  void isFetchAll_whenFetchAllRowsFalse_returnsFalse() {
    SqlToolConfig tool = fetchConfig(null, false);

    assertFalse(tool.isFetchAll(), "fetchAllRows=false should disable fetch-all");
  }

  @Test
  void isFetchAll_whenFetchAllRowsNull_returnsFalse() {
    SqlToolConfig tool = fetchConfig(null, null);

    assertFalse(tool.isFetchAll(), "fetchAllRows=null should disable fetch-all");
  }

  @Test
  void isFetchAll_whenBothFetchAllRowsAndRowsToFetchSet_returnsFalse() {
    // Precedence rule: rowsToFetch takes priority over fetchAllRows
    SqlToolConfig tool = fetchConfig(100, true);

    assertFalse(tool.isFetchAll(),
        "rowsToFetch takes precedence over fetchAllRows - should disable fetch-all");
  }

  @Test
  void isFetchAll_whenRowsToFetchSetAndFetchAllRowsFalse_returnsFalse() {
    SqlToolConfig tool = fetchConfig(50, false);

    assertFalse(tool.isFetchAll(), "Both disabled should return false");
  }

  @Test
  void isFetchAll_whenRowsToFetchSetAndFetchAllRowsNull_returnsFalse() {
    SqlToolConfig tool = fetchConfig(200, null);

    assertFalse(tool.isFetchAll(),
        "rowsToFetch set should disable fetch-all regardless of fetchAllRows");
  }

  @Test
  void emptyResultError_defaultsToNullSoEmptyResultsStaySuccessful() {
    assertNull(fetchConfig(null, null).emptyResultError());
  }

  @Test
  void withEmptyResultError_setsMessageAndLeavesEverythingElseAlone() {
    SqlToolConfig original = fetchConfig(200, true);

    SqlToolConfig withError = original.withEmptyResultError("nothing found");

    assertEquals("nothing found", withError.emptyResultError());
    assertEquals(original, withError.withEmptyResultError(null),
        "clearing the message should restore the original config");
  }

  /** All the fetch-precedence cases differ only in these two values. */
  private static SqlToolConfig fetchConfig(Integer rowsToFetch, Boolean fetchAllRows) {
    return new SqlToolConfig(
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
        rowsToFetch,
        fetchAllRows,
        null,
        null,
        null);
  }
}
