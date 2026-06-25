package com.ibm.ibmi.mcp.format;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type-aware formatting helpers for SQL result presentation.
 */
public final class SqlFormattingUtils {

  private static final Set<String> NUMERIC_TYPES = Set.of(
      "INTEGER", "SMALLINT", "BIGINT", "DECIMAL", "NUMERIC", "FLOAT", "REAL",
      "DOUBLE", "DECFLOAT", "INT", "DEC");

  private static final Set<String> TEXT_TYPES = Set.of(
      "VARCHAR", "CHAR", "CHARACTER", "CLOB", "VARGRAPHIC", "GRAPHIC", "DBCLOB",
      "BLOB", "BINARY", "VARBINARY");

  private static final Set<String> TEMPORAL_TYPES = Set.of(
      "DATE", "TIME", "TIMESTAMP", "TIMESTMP");

  private SqlFormattingUtils() {}

  public static String formatColumnHeader(String columnName, String columnType) {
    if (columnType == null || columnType.isBlank()) {
      return columnName;
    }
    String baseType = columnType.split("\\(")[0].trim().toUpperCase();
    return columnName + " (" + baseType + ")";
  }

  public static String getColumnAlignment(String columnType) {
    if (columnType == null || columnType.isBlank()) {
      return "left";
    }
    String normalizedType = columnType.toUpperCase().split("\\(")[0].trim();
    if (NUMERIC_TYPES.contains(normalizedType)) {
      return "right";
    }
    if (TEXT_TYPES.contains(normalizedType) || TEMPORAL_TYPES.contains(normalizedType)) {
      return "left";
    }
    return "left";
  }

  public static Map<String, String> buildColumnAlignmentMap(List<Map<String, Object>> columns) {
    Map<String, String> alignmentMap = new LinkedHashMap<>();
    for (Map<String, Object> column : columns) {
      String name = (String) column.get("name");
      String type = (String) column.get("type");
      alignmentMap.put(name, getColumnAlignment(type));
    }
    return alignmentMap;
  }
}
