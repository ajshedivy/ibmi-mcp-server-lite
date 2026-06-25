package com.ibm.ibmi.mcp.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats tabular data into markdown, ASCII, grid, or compact table styles.
 */
public final class TableFormatter {

  private static final int DEFAULT_MAX_WIDTH = 50;
  private static final int DEFAULT_MIN_WIDTH = 3;
  private static final int DEFAULT_PADDING = 1;
  private static final String DEFAULT_NULL_REPLACEMENT = "-";

  private TableFormatter() {}

  public record TableResult(String table, Map<String, Integer> nullCounts) {}

  private record ColumnInfo(String name, int width, String alignment) {}

  public static TableResult formatRawWithMetadata(
      List<String> headers,
      List<List<String>> rows,
      String style,
      Map<String, String> alignment,
      String nullReplacement,
      int maxWidth,
      boolean truncate) {

    if (headers == null || headers.isEmpty()) {
      throw new IllegalArgumentException("Headers must be a non-empty list");
    }
    if (rows == null) {
      throw new IllegalArgumentException("Rows must be a list");
    }
    if (rows.isEmpty()) {
      return new TableResult("", Map.of());
    }

    Map<String, Integer> nullCounts = new HashMap<>();
    String nullRep = nullReplacement != null ? nullReplacement : DEFAULT_NULL_REPLACEMENT;
    int cellMaxWidth = maxWidth > 0 ? maxWidth : DEFAULT_MAX_WIDTH;
    Map<String, String> alignMap = alignment != null ? alignment : Map.of();
    String tableStyle = style != null ? style : "markdown";

    List<List<String>> stringRows = new ArrayList<>();
    for (List<String> row : rows) {
      List<String> stringRow = new ArrayList<>();
      for (int colIndex = 0; colIndex < headers.size(); colIndex++) {
        String cell = colIndex < row.size() ? row.get(colIndex) : null;
        stringRow.add(stringify(cell, nullRep, nullCounts, Integer.toString(colIndex)));
      }
      stringRows.add(stringRow);
    }

    List<ColumnInfo> columns = calculateColumns(headers, stringRows, alignMap, cellMaxWidth);
    String table = renderTable(columns, headers, stringRows, tableStyle, truncate, DEFAULT_PADDING);

    return new TableResult(table, Map.copyOf(nullCounts));
  }

  private static String stringify(
      String value, String nullReplacement, Map<String, Integer> nullCounts, String columnKey) {
    if (value == null) {
      nullCounts.merge(columnKey, 1, Integer::sum);
      return nullReplacement;
    }
    return value;
  }

  private static List<ColumnInfo> calculateColumns(
      List<String> headers, List<List<String>> rows, Map<String, String> alignment, int maxWidth) {

    List<ColumnInfo> columns = new ArrayList<>();
    for (int index = 0; index < headers.size(); index++) {
      String header = headers.get(index);
      String align = alignment.getOrDefault(header,
          alignment.getOrDefault(Integer.toString(index), "left"));

      int headerWidth = header.length();
      final int colIndex = index;
      int maxContentWidth = rows.stream()
          .mapToInt(row -> colIndex < row.size() ? row.get(colIndex).length() : 0)
          .max()
          .orElse(0);

      int width = Math.max(Math.max(headerWidth, maxContentWidth), DEFAULT_MIN_WIDTH);
      if (width > maxWidth) {
        width = maxWidth;
      }
      columns.add(new ColumnInfo(header, width, align));
    }
    return columns;
  }

  private static String renderTable(
      List<ColumnInfo> columns,
      List<String> headers,
      List<List<String>> rows,
      String style,
      boolean truncate,
      int padding) {

    return switch (style) {
      case "ascii" -> renderAscii(columns, headers, rows, truncate, padding);
      case "grid" -> renderGrid(columns, headers, rows, truncate, padding);
      case "compact" -> renderCompact(columns, headers, rows, truncate, padding);
      case "markdown" -> renderMarkdown(columns, headers, rows, truncate, padding);
      default -> renderMarkdown(columns, headers, rows, truncate, padding);
    };
  }

  private static String renderMarkdown(
      List<ColumnInfo> columns, List<String> headers, List<List<String>> rows,
      boolean truncate, int padding) {
    List<String> lines = new ArrayList<>();
    String pad = " ".repeat(padding);

    List<String> headerCells = new ArrayList<>();
    for (int i = 0; i < headers.size(); i++) {
      headerCells.add(formatCell(headers.get(i), columns.get(i), truncate));
    }
    lines.add("|" + pad + String.join(pad + "|" + pad, headerCells) + pad + "|");

    List<String> separators = new ArrayList<>();
    for (ColumnInfo col : columns) {
      separators.add("-".repeat(col.width()));
    }
    lines.add("|" + pad + String.join(pad + "|" + pad, separators) + pad + "|");

    for (List<String> row : rows) {
      List<String> cells = new ArrayList<>();
      for (int i = 0; i < columns.size(); i++) {
        String cell = i < row.size() ? row.get(i) : "";
        cells.add(formatCell(cell, columns.get(i), truncate));
      }
      lines.add("|" + pad + String.join(pad + "|" + pad, cells) + pad + "|");
    }
    return String.join("\n", lines);
  }

  private static String renderAscii(
      List<ColumnInfo> columns, List<String> headers, List<List<String>> rows,
      boolean truncate, int padding) {
    List<String> lines = new ArrayList<>();
    String pad = " ".repeat(padding);

    lines.add("+" + columns.stream()
        .map(col -> "-".repeat(col.width() + padding * 2))
        .reduce((a, b) -> a + "+" + b)
        .orElse("") + "+");

    List<String> headerCells = new ArrayList<>();
    for (int i = 0; i < headers.size(); i++) {
      headerCells.add(formatCell(headers.get(i), columns.get(i), truncate));
    }
    lines.add("|" + pad + String.join(pad + "|" + pad, headerCells) + pad + "|");

    lines.add("+" + columns.stream()
        .map(col -> "-".repeat(col.width() + padding * 2))
        .reduce((a, b) -> a + "+" + b)
        .orElse("") + "+");

    for (List<String> row : rows) {
      List<String> cells = new ArrayList<>();
      for (int i = 0; i < columns.size(); i++) {
        String cell = i < row.size() ? row.get(i) : "";
        cells.add(formatCell(cell, columns.get(i), truncate));
      }
      lines.add("|" + pad + String.join(pad + "|" + pad, cells) + pad + "|");
    }

    lines.add("+" + columns.stream()
        .map(col -> "-".repeat(col.width() + padding * 2))
        .reduce((a, b) -> a + "+" + b)
        .orElse("") + "+");

    return String.join("\n", lines);
  }

  private static String renderGrid(
      List<ColumnInfo> columns, List<String> headers, List<List<String>> rows,
      boolean truncate, int padding) {
    List<String> lines = new ArrayList<>();
    String pad = " ".repeat(padding);

    lines.add("┌" + columns.stream()
        .map(col -> "─".repeat(col.width() + padding * 2))
        .reduce((a, b) -> a + "┬" + b)
        .orElse("") + "┐");

    List<String> headerCells = new ArrayList<>();
    for (int i = 0; i < headers.size(); i++) {
      headerCells.add(formatCell(headers.get(i), columns.get(i), truncate));
    }
    lines.add("│" + pad + String.join(pad + "│" + pad, headerCells) + pad + "│");

    lines.add("├" + columns.stream()
        .map(col -> "─".repeat(col.width() + padding * 2))
        .reduce((a, b) -> a + "┼" + b)
        .orElse("") + "┤");

    for (List<String> row : rows) {
      List<String> cells = new ArrayList<>();
      for (int i = 0; i < columns.size(); i++) {
        String cell = i < row.size() ? row.get(i) : "";
        cells.add(formatCell(cell, columns.get(i), truncate));
      }
      lines.add("│" + pad + String.join(pad + "│" + pad, cells) + pad + "│");
    }

    lines.add("└" + columns.stream()
        .map(col -> "─".repeat(col.width() + padding * 2))
        .reduce((a, b) -> a + "┴" + b)
        .orElse("") + "┘");

    return String.join("\n", lines);
  }

  private static String renderCompact(
      List<ColumnInfo> columns, List<String> headers, List<List<String>> rows,
      boolean truncate, int padding) {
    List<String> lines = new ArrayList<>();
    String pad = " ".repeat(padding * 2);

    List<String> headerCells = new ArrayList<>();
    for (int i = 0; i < headers.size(); i++) {
      headerCells.add(formatCell(headers.get(i), columns.get(i), truncate));
    }
    lines.add(String.join(pad, headerCells));

    for (List<String> row : rows) {
      List<String> cells = new ArrayList<>();
      for (int i = 0; i < columns.size(); i++) {
        String cell = i < row.size() ? row.get(i) : "";
        cells.add(formatCell(cell, columns.get(i), truncate));
      }
      lines.add(String.join(pad, cells));
    }
    return String.join("\n", lines);
  }

  private static String formatCell(String content, ColumnInfo column, boolean truncate) {
    String text = content != null ? content : "";
    if (truncate && text.length() > column.width()) {
      text = text.substring(0, column.width() - 3) + "...";
    }

    int padding = column.width() - text.length();
    if (padding <= 0) {
      return text;
    }

    return switch (column.alignment()) {
      case "right" -> " ".repeat(padding) + text;
      case "center" -> {
        int leftPad = padding / 2;
        int rightPad = padding - leftPad;
        yield " ".repeat(leftPad) + text + " ".repeat(rightPad);
      }
      default -> text + " ".repeat(padding);
    };
  }
}
