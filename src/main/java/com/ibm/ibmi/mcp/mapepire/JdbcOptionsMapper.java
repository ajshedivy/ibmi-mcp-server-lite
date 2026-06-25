package com.ibm.ibmi.mcp.mapepire;

import java.util.Map;
import java.util.Properties;

import io.github.mapepire_ibmi.types.JDBCOptions;

/**
 * Converts merged YAML/env JDBC option maps into mapepire {@link JDBCOptions}.
 */
public final class JdbcOptionsMapper {

  private JdbcOptionsMapper() {}

  public static JDBCOptions toMapepire(Map<String, Object> options) {
    Properties props = new Properties();
    for (Map.Entry<String, Object> entry : options.entrySet()) {
      if ("libraries".equals(entry.getKey())) {
        props.put("libraries", entry.getValue());
      } else {
        props.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }
    return new JDBCOptions(props);
  }
}
