package com.ibm.ibmi.mcp.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses {@code DB2i_JDBC_OPTIONS} env var syntax: semicolon-separated {@code key=value} pairs.
 *
 * <p>Example: {@code naming=system;date format=iso;libraries=MYLIB,DEVDATA}
 *
 * <p>Rules mirror the reference Node.js server ({@code parseJdbcOptionsString} in
 * {@code packages/server/src/config/index.ts}).
 */
public final class JdbcOptionsParser {

  private JdbcOptionsParser() {}

  /**
   * Parses a raw {@code DB2i_JDBC_OPTIONS} string into a JDBC options map.
   *
   * @return insertion-ordered map; empty when {@code raw} is null/blank
   */
  public static Map<String, Object> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Collections.emptyMap();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (String segment : raw.split(";")) {
      String pair = segment.trim();
      if (pair.isEmpty()) {
        continue;
      }
      int eqIdx = pair.indexOf('=');
      if (eqIdx == -1) {
        throw new ConfigException(
            "Invalid DB2i_JDBC_OPTIONS: malformed pair \"" + pair + "\" — expected key=value");
      }
      String key = pair.substring(0, eqIdx).trim();
      String value = pair.substring(eqIdx + 1).trim();
      if (key.isEmpty()) {
        throw new ConfigException(
            "Invalid DB2i_JDBC_OPTIONS: empty key in pair \"" + pair + "\"");
      }
      if ("libraries".equals(key)) {
        result.put(key, parseLibrariesCsv(value));
      } else {
        result.put(key, value);
      }
    }
    return result.isEmpty() ? Collections.emptyMap() : result;
  }

  static List<String> parseLibrariesCsv(String csv) {
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }
}
