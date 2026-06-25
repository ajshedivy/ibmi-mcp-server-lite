package com.ibm.ibmi.mcp.format;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SqlMarkdownFormatterTest {

  @Test
  void formatsSuccessfulResultWithAllSections() {
    Map<String, Object> output = sampleOutput(5, 3);

    String markdown = SqlMarkdownFormatter.format(output, "markdown", 3);

    assertTrue(markdown.contains("## active_jobs"));
    assertTrue(markdown.contains("[!TIP]"));
    assertTrue(markdown.contains("Found **5 rows**"));
    assertTrue(markdown.contains("### SQL Statement"));
    assertTrue(markdown.contains("```sql"));
    assertTrue(markdown.contains("SELECT * FROM jobs WHERE id = ?"));
    assertTrue(markdown.contains("### Parameters"));
    assertTrue(markdown.contains("`limit`: 10"));
    assertTrue(markdown.contains("Showing 3 of 5 rows. 2 rows omitted."));
    assertTrue(markdown.contains("### Results"));
    assertTrue(markdown.contains("| JOB_NAME (VARCHAR) |"));
    assertTrue(markdown.contains("### Summary"));
    assertTrue(markdown.contains("**Total rows**: 5"));
    assertTrue(markdown.contains("**NULL values**"));
  }

  @Test
  void truncatesLongSqlStatement() {
    String longSql = "SELECT " + "X".repeat(600);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("toolName", "test");
    metadata.put("executionTime", 10);
    metadata.put("parameterCount", 0);
    metadata.put("sqlStatement", longSql);
    metadata.put("columns", List.of(Map.of("name", "A", "type", "INTEGER")));

    Map<String, Object> output = Map.of(
        "success", true,
        "data", List.of(Map.of("A", 1)),
        "metadata", metadata);

    String markdown = SqlMarkdownFormatter.format(output, "markdown", 100);
    assertTrue(markdown.contains("..."));
    assertFalse(markdown.contains(longSql));
  }

  @Test
  void formatsEmptyResult() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("toolName", "empty_query");
    metadata.put("executionTime", 42);
    metadata.put("parameterCount", 0);

    Map<String, Object> output = Map.of(
        "success", true,
        "data", List.of(),
        "metadata", metadata);

    String markdown = SqlMarkdownFormatter.format(output, "markdown", 100);

    assertTrue(markdown.contains("No rows returned from the query."));
    assertTrue(markdown.contains("### Execution Summary"));
    assertTrue(markdown.contains("**Execution time:** 42ms"));
    assertFalse(markdown.contains("### Results"));
  }

  private static Map<String, Object> sampleOutput(int rowCount, int maxDisplay) {
    List<Map<String, Object>> rows = new java.util.ArrayList<>();
    for (int i = 0; i < rowCount; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("JOB_NAME", i % 2 == 0 ? "JOB" + i : null);
      row.put("JOB_ID", i + 1);
      rows.add(row);
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("toolName", "active_jobs");
    metadata.put("executionTime", 150);
    metadata.put("parameterCount", 1);
    metadata.put("sqlStatement", "SELECT * FROM jobs WHERE id = ?");
    metadata.put("parameters", Map.of("limit", 10));
    metadata.put("columns", List.of(
        Map.of("name", "JOB_NAME", "type", "VARCHAR(50)", "label", "JOB_NAME"),
        Map.of("name", "JOB_ID", "type", "INTEGER", "label", "JOB_ID")));

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("success", true);
    output.put("data", rows);
    output.put("metadata", metadata);
    return output;
  }
}
