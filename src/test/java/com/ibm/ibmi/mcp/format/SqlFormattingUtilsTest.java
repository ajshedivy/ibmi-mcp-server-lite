package com.ibm.ibmi.mcp.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SqlFormattingUtilsTest {

  @Test
  void formatColumnHeaderWithType() {
    assertEquals("EMPLOYEE_ID (INTEGER)", SqlFormattingUtils.formatColumnHeader("EMPLOYEE_ID", "INTEGER"));
    assertEquals("NAME (VARCHAR)", SqlFormattingUtils.formatColumnHeader("NAME", "VARCHAR(50)"));
    assertEquals("STATUS", SqlFormattingUtils.formatColumnHeader("STATUS", null));
  }

  @Test
  void getColumnAlignment() {
    assertEquals("right", SqlFormattingUtils.getColumnAlignment("INTEGER"));
    assertEquals("right", SqlFormattingUtils.getColumnAlignment("DECIMAL"));
    assertEquals("left", SqlFormattingUtils.getColumnAlignment("VARCHAR"));
    assertEquals("left", SqlFormattingUtils.getColumnAlignment("TIMESTAMP"));
    assertEquals("left", SqlFormattingUtils.getColumnAlignment("UNKNOWN"));
    assertEquals("left", SqlFormattingUtils.getColumnAlignment(null));
  }

  @Test
  void buildColumnAlignmentMap() {
    List<Map<String, Object>> columns = List.of(
        Map.of("name", "ID", "type", "INTEGER"),
        Map.of("name", "NAME", "type", "VARCHAR"),
        Map.of("name", "SALARY", "type", "DECIMAL"));

    Map<String, String> alignment = SqlFormattingUtils.buildColumnAlignmentMap(columns);
    assertEquals("right", alignment.get("ID"));
    assertEquals("left", alignment.get("NAME"));
    assertEquals("right", alignment.get("SALARY"));
  }
}
