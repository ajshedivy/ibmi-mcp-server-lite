package com.ibm.ibmi.mcp.mapepire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import io.github.mapepire_ibmi.types.JDBCOptions;

class JdbcOptionsMapperTest {

  @Test
  void mapsLibrariesAsListAndOtherKeysAsStrings() {
    JDBCOptions opts = JdbcOptionsMapper.toMapepire(Map.of(
        "libraries", List.of("MYLIB", "DEVDATA"),
        "naming", "system"));

    Properties props = opts.getOptions();
    Object libraries = props.get("libraries");
    assertInstanceOf(List.class, libraries);
    assertEquals(List.of("MYLIB", "DEVDATA"), libraries);
    assertEquals("system", props.get("naming"));
  }

  @Test
  void stringifiesNonLibrariesValues() {
    JDBCOptions opts = JdbcOptionsMapper.toMapepire(Map.of("date format", "iso"));
    assertEquals("iso", opts.getOptions().get("date format"));
  }
}
