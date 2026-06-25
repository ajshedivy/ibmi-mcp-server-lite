package com.ibm.ibmi.mcp.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TableFormatterTest {

  @Test
  void rendersMarkdownTableWithNullReplacement() {
    List<String> row1 = new ArrayList<>();
    row1.add("Alice");
    row1.add("30");
    row1.add(null);
    List<String> headers = List.of("Name", "Age", "Salary");
    List<List<String>> rows = List.of(
        row1,
        List.of("Bob", "25", "50000"));

    TableFormatter.TableResult result = TableFormatter.formatRawWithMetadata(
        headers, rows, "markdown", Map.of(), "-", 50, true);

    assertTrue(result.table().contains("Name"));
    assertTrue(result.table().contains(" - "));
    assertEquals(1, result.nullCounts().get("2"));
  }

  @Test
  void truncatesLongCellContent() {
    String longValue = "A".repeat(60);
    List<String> headers = List.of("Data");
    List<List<String>> rows = List.of(List.of(longValue));

    TableFormatter.TableResult result = TableFormatter.formatRawWithMetadata(
        headers, rows, "markdown", Map.of(), "-", 50, true);

    assertTrue(result.table().contains("..."));
    assertFalse(result.table().contains(longValue));
  }

  @Test
  void appliesRightAlignment() {
    List<String> headers = List.of("Amount");
    List<List<String>> rows = List.of(List.of("42"));
    Map<String, String> alignment = Map.of("Amount", "right");

    TableFormatter.TableResult result = TableFormatter.formatRawWithMetadata(
        headers, rows, "markdown", alignment, "-", 50, true);

    assertTrue(result.table().contains(" 42"));
  }

  @Test
  void supportsAsciiStyle() {
    List<String> headers = List.of("A", "B");
    List<List<String>> rows = List.of(List.of("1", "2"));

    TableFormatter.TableResult result = TableFormatter.formatRawWithMetadata(
        headers, rows, "ascii", Map.of(), "-", 50, true);

    assertTrue(result.table().startsWith("+"));
    assertTrue(result.table().contains("|"));
  }

  @Test
  void emptyRowsReturnsEmptyTable() {
    TableFormatter.TableResult result = TableFormatter.formatRawWithMetadata(
        List.of("A"), List.of(), "markdown", Map.of(), "-", 50, true);
    assertEquals("", result.table());
    assertTrue(result.nullCounts().isEmpty());
  }
}
