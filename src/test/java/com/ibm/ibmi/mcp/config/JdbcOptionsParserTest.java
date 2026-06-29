package com.ibm.ibmi.mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JdbcOptionsParserTest {

  @Test
  void blankOrNullReturnsEmptyMap() {
    assertTrue(JdbcOptionsParser.parse(null).isEmpty());
    assertTrue(JdbcOptionsParser.parse("").isEmpty());
    assertTrue(JdbcOptionsParser.parse("   ").isEmpty());
  }

  @Test
  void parsesNamingAndLibraries() {
    Map<String, Object> opts = JdbcOptionsParser.parse("naming=system;libraries=MYLIB,DEVDATA");
    assertEquals("system", opts.get("naming"));
    assertEquals(List.of("MYLIB", "DEVDATA"), opts.get("libraries"));
  }

  @Test
  void librariesCsvTrimsAndDropsEmpties() {
    Map<String, Object> opts = JdbcOptionsParser.parse("libraries=  MYLIB ,  DEVDATA  , , QGPL ");
    assertEquals(List.of("MYLIB", "DEVDATA", "QGPL"), opts.get("libraries"));
  }

  @Test
  void valueMayContainEquals() {
    Map<String, Object> opts = JdbcOptionsParser.parse("key=part1=part2");
    assertEquals("part1=part2", opts.get("key"));
  }

  @Test
  void keyWithSpacesIsAllowed() {
    Map<String, Object> opts = JdbcOptionsParser.parse("date format=iso");
    assertEquals("iso", opts.get("date format"));
  }

  @Test
  void emptySegmentsAreIgnored() {
    Map<String, Object> opts = JdbcOptionsParser.parse("naming=system;;libraries=MYLIB");
    assertEquals(2, opts.size());
    assertEquals("system", opts.get("naming"));
    assertEquals(List.of("MYLIB"), opts.get("libraries"));
  }

  @Test
  void malformedPairWithoutEqualsThrows() {
    ConfigException e = assertThrows(ConfigException.class, () -> JdbcOptionsParser.parse("naming"));
    assertTrue(e.getMessage().contains("malformed pair \"naming\""));
  }

  @Test
  void emptyKeyThrows() {
    ConfigException e = assertThrows(ConfigException.class, () -> JdbcOptionsParser.parse("=value"));
    assertTrue(e.getMessage().contains("empty key"));
  }
}
