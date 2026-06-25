package com.ibm.ibmi.mcp.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats a StandardSqlToolOutput map as a human-readable markdown document.
 */
public final class SqlMarkdownFormatter {

  private static final int SQL_TRUNCATION_LIMIT = 500;
  private static final int PARAM_VALUE_TRUNCATION_LIMIT = 100;

  private SqlMarkdownFormatter() {}

  @SuppressWarnings("unchecked")
  public static String format(Map<String, Object> output, String tableFormat, int maxDisplayRows) {
    Boolean success = (Boolean) output.get("success");
    List<Map<String, Object>> data = castDataRows(output.get("data"));
    Map<String, Object> metadata = output.get("metadata") instanceof Map<?, ?> m
        ? (Map<String, Object>) m
        : Map.of();

    if (!Boolean.TRUE.equals(success) || data == null) {
      return "Query failed.";
    }

    int rowCount = data.size();
    MarkdownBuilder md = new MarkdownBuilder();

    Object toolName = metadata.get("toolName");
    if (toolName != null) {
      md.h2(toolName.toString());
    }

    md.alert("tip", "✅ Query completed successfully")
        .blankLine()
        .paragraph("Found **" + rowCount + " row" + (rowCount != 1 ? "s" : "")
            + "** from the database query");

    Object sqlStatement = metadata.get("sqlStatement");
    if (sqlStatement != null) {
      String sql = sqlStatement.toString();
      if (sql.length() > SQL_TRUNCATION_LIMIT) {
        sql = sql.substring(0, SQL_TRUNCATION_LIMIT - 3) + "...";
      }
      md.h3("SQL Statement").codeBlock(sql, "sql");
    }

    Map<String, Object> parameters = metadata.get("parameters") instanceof Map<?, ?> p
        ? (Map<String, Object>) p
        : Map.of();
    if (!parameters.isEmpty()) {
      List<String> paramList = new ArrayList<>();
      for (Map.Entry<String, Object> entry : parameters.entrySet()) {
        String displayValue = formatParameterValue(entry.getValue());
        paramList.add("`" + entry.getKey() + "`: " + displayValue);
      }
      md.h3("Parameters").list(paramList);
    }

    if (rowCount == 0) {
      Object executionTime = metadata.get("executionTime");
      md.paragraph("No rows returned from the query.")
          .h3("Execution Summary")
          .keyValue("Execution time", executionTime != null ? executionTime + "ms" : "N/A")
          .keyValue("Parameters used", String.valueOf(metadata.getOrDefault("parameterCount", 0)));
      return md.build();
    }

    List<Map<String, Object>> columns = metadata.get("columns") instanceof List<?> colList
        ? castColumnList(colList)
        : List.of();

    if (columns.isEmpty() && !data.isEmpty()) {
      columns = deriveColumnsFromRow(data.get(0));
    }

    int displayCount = Math.min(rowCount, maxDisplayRows);
    List<Map<String, Object>> displayRows = data.subList(0, displayCount);
    int columnCount = columns.size();

    List<String> headers = new ArrayList<>();
    Map<String, String> alignment = new LinkedHashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      Map<String, Object> col = columns.get(i);
      String name = stringOrDefault(col.get("name"), "column_" + i);
      String label = stringOrDefault(col.get("label"), name);
      String type = col.get("type") != null ? col.get("type").toString() : null;
      String header = SqlFormattingUtils.formatColumnHeader(label, type);
      headers.add(header);
      String align = SqlFormattingUtils.getColumnAlignment(type);
      alignment.put(header, align);
      alignment.put(Integer.toString(i), align);
    }

    List<List<String>> tableRows = new ArrayList<>();
    for (Map<String, Object> row : displayRows) {
      List<String> tableRow = new ArrayList<>();
      for (Map<String, Object> col : columns) {
        String colName = col.get("name").toString();
        Object value = row.get(colName);
        tableRow.add(value == null ? null : String.valueOf(value));
      }
      tableRows.add(tableRow);
    }

    TableFormatter.TableResult tableResult = TableFormatter.formatRawWithMetadata(
        headers, tableRows, tableFormat, alignment, "-", 50, true);

    if (displayCount < rowCount) {
      md.alert("note",
          "Showing " + displayCount + " of " + rowCount + " rows. "
              + (rowCount - displayCount) + " rows omitted.");
    }

    md.h3("Results").raw(tableResult.table()).raw("\n\n");

    Map<String, Integer> nullCounts = tableResult.nullCounts();
    int totalNulls = nullCounts.values().stream().mapToInt(Integer::intValue).sum();

    List<String> summaryItems = new ArrayList<>();
    summaryItems.add("**Total rows**: " + rowCount);
    summaryItems.add("**Columns**: " + columnCount);

    Object executionTime = metadata.get("executionTime");
    if (executionTime != null) {
      summaryItems.add("**Execution time**: " + executionTime + "ms");
    }

    if (totalNulls > 0) {
      List<String> nullDetails = new ArrayList<>();
      for (Map.Entry<String, Integer> entry : nullCounts.entrySet()) {
        if (entry.getValue() > 0) {
          String colName = resolveColumnName(entry.getKey(), columns);
          nullDetails.add(colName + " (" + entry.getValue() + ")");
        }
      }
      summaryItems.add("**NULL values**: " + totalNulls + " total - "
          + String.join(", ", nullDetails));
    }

    if (metadata.containsKey("affectedRows")) {
      summaryItems.add("**Affected rows**: " + metadata.get("affectedRows"));
    }

    Object parameterCount = metadata.get("parameterCount");
    if (parameterCount instanceof Number n && n.intValue() > 0) {
      summaryItems.add("**Parameters processed**: " + parameterCount);
    }

    md.h3("Summary").list(summaryItems);
    return md.build();
  }

  private static String formatParameterValue(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof String s && s.length() > PARAM_VALUE_TRUNCATION_LIMIT) {
      return s.substring(0, PARAM_VALUE_TRUNCATION_LIMIT - 3) + "...";
    }
    return String.valueOf(value);
  }

  private static String stringOrDefault(Object value, String defaultValue) {
    return value != null ? value.toString() : defaultValue;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castColumnList(List<?> colList) {
    List<Map<String, Object>> columns = new ArrayList<>();
    for (Object item : colList) {
      if (item instanceof Map<?, ?> map) {
        columns.add((Map<String, Object>) map);
      }
    }
    return columns;
  }

  private static List<Map<String, Object>> deriveColumnsFromRow(Map<String, Object> row) {
    List<Map<String, Object>> columns = new ArrayList<>();
    for (String key : row.keySet()) {
      Map<String, Object> col = new LinkedHashMap<>();
      col.put("name", key);
      col.put("label", key);
      columns.add(col);
    }
    return columns;
  }

  private static String resolveColumnName(String key, List<Map<String, Object>> columns) {
    try {
      int colIndex = Integer.parseInt(key);
      if (colIndex >= 0 && colIndex < columns.size()) {
        Object name = columns.get(colIndex).get("name");
        return name != null ? name.toString() : key;
      }
    } catch (NumberFormatException ignored) {
      // key is already a column name
    }
    return key;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castDataRows(Object rawData) {
    if (!(rawData instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        rows.add((Map<String, Object>) map);
      }
    }
    return rows;
  }
}
