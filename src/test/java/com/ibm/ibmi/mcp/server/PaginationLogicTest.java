package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.config.SqlToolConfig;

/**
 * Unit tests for pagination truncation and clipping logic.
 * 
 * <p>These tests verify the core pagination algorithm without requiring a live IBM i connection
 * by simulating the accumulation and truncation logic.
 */
class PaginationLogicTest {

  /**
   * Simulates the pagination accumulation logic from SqlToolHandler.executePaginatedQuery.
   * 
   * @param pages list of page results (each page is a list of rows)
   * @param isDoneFlags corresponding isDone flag for each page
   * @return result containing accumulated rows and truncation status
   */
  private static PaginationResult simulatePagination(List<List<Object>> pages, List<Boolean> isDoneFlags) {
    List<Object> accumulated = new ArrayList<>();
    boolean isDone = false;
    
    for (int i = 0; i < pages.size(); i++) {
      List<Object> page = pages.get(i);
      isDone = isDoneFlags.get(i);
      
      accumulated.addAll(page);
      
      // Stop if done or reached limit (simulates the while condition)
      if (isDone || accumulated.size() >= SqlToolConfig.MAX_PAGINATION_ROWS) {
        break;
      }
    }
    
    // Compute truncated flag
    boolean truncated = !isDone || accumulated.size() > SqlToolConfig.MAX_PAGINATION_ROWS;
    
    // Hard-clip to MAX_PAGINATION_ROWS
    if (accumulated.size() > SqlToolConfig.MAX_PAGINATION_ROWS) {
      accumulated = accumulated.subList(0, SqlToolConfig.MAX_PAGINATION_ROWS);
    }
    
    return new PaginationResult(accumulated, truncated);
  }

  @Test
  void pagination_whenSinglePageCompletes_notTruncated() {
    // Single page with 100 rows, query is done
    List<List<Object>> pages = List.of(
        createPage(100)
    );
    List<Boolean> isDoneFlags = List.of(true);
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(100, result.rows().size(), "Should have all 100 rows");
    assertFalse(result.truncated(), "Should not be truncated when query completes");
  }

  @Test
  void pagination_whenMultiplePagesComplete_notTruncated() {
    // Three pages totaling 3000 rows, query completes
    List<List<Object>> pages = List.of(
        createPage(1000),
        createPage(1000),
        createPage(1000)
    );
    List<Boolean> isDoneFlags = List.of(false, false, true);
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(3000, result.rows().size(), "Should have all 3000 rows");
    assertFalse(result.truncated(), "Should not be truncated when query completes under limit");
  }

  @Test
  void pagination_whenExactlyAtLimit_truncated() {
    // Exactly MAX_PAGINATION_ROWS (30,000), but query has more data
    List<List<Object>> pages = new ArrayList<>();
    List<Boolean> isDoneFlags = new ArrayList<>();
    
    // 30 pages of 1000 rows each = 30,000 rows
    for (int i = 0; i < 30; i++) {
      pages.add(createPage(1000));
      isDoneFlags.add(false); // Query not done
    }
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(SqlToolConfig.MAX_PAGINATION_ROWS, result.rows().size(), 
        "Should have exactly MAX_PAGINATION_ROWS");
    assertTrue(result.truncated(), "Should be truncated when at limit with more data available");
  }

  @Test
  void pagination_whenExceedsLimit_clippedAndTruncated() {
    // 31 pages of 1000 rows = 31,000 rows, should clip to 30,000
    List<List<Object>> pages = new ArrayList<>();
    List<Boolean> isDoneFlags = new ArrayList<>();
    
    for (int i = 0; i < 31; i++) {
      pages.add(createPage(1000));
      isDoneFlags.add(i == 30); // Last page marks done
    }
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(SqlToolConfig.MAX_PAGINATION_ROWS, result.rows().size(), 
        "Should be hard-clipped to MAX_PAGINATION_ROWS");
    assertTrue(result.truncated(), "Should be truncated when exceeding limit");
  }

  @Test
  void pagination_whenQueryNotDoneUnderLimit_truncated() {
    // 5000 rows but query indicates more data available
    List<List<Object>> pages = List.of(
        createPage(1000),
        createPage(1000),
        createPage(1000),
        createPage(1000),
        createPage(1000)
    );
    List<Boolean> isDoneFlags = List.of(false, false, false, false, false);
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(5000, result.rows().size(), "Should have all fetched rows");
    assertTrue(result.truncated(), "Should be truncated when query indicates more data");
  }

  @Test
  void pagination_whenEmptyResult_notTruncated() {
    // Empty result set
    List<List<Object>> pages = List.of(
        List.of()
    );
    List<Boolean> isDoneFlags = List.of(true);
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(0, result.rows().size(), "Should have no rows");
    assertFalse(result.truncated(), "Empty result should not be truncated");
  }

  @Test
  void pagination_whenLastPagePartial_notTruncated() {
    // 2500 rows total (2 full pages + 1 partial), query completes
    List<List<Object>> pages = List.of(
        createPage(1000),
        createPage(1000),
        createPage(500)
    );
    List<Boolean> isDoneFlags = List.of(false, false, true);
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(2500, result.rows().size(), "Should have all 2500 rows");
    assertFalse(result.truncated(), "Should not be truncated when query completes");
  }

  @Test
  void pagination_whenSlightlyOverLimit_clippedToExactLimit() {
    // 30,100 rows (30 full pages + 1 partial of 100), should clip to exactly 30,000
    List<List<Object>> pages = new ArrayList<>();
    List<Boolean> isDoneFlags = new ArrayList<>();
    
    for (int i = 0; i < 30; i++) {
      pages.add(createPage(1000));
      isDoneFlags.add(false);
    }
    pages.add(createPage(100));
    isDoneFlags.add(true);
    
    PaginationResult result = simulatePagination(pages, isDoneFlags);
    
    assertEquals(SqlToolConfig.MAX_PAGINATION_ROWS, result.rows().size(), 
        "Should be clipped to exactly MAX_PAGINATION_ROWS");
    assertTrue(result.truncated(), "Should be truncated when clipping occurs");
  }

  // Helper methods

  private static List<Object> createPage(int size) {
    List<Object> page = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      page.add(Map.of("id", i, "data", "row_" + i));
    }
    return page;
  }

  private record PaginationResult(List<Object> rows, boolean truncated) {}
}