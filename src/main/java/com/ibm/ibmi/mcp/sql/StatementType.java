package com.ibm.ibmi.mcp.sql;

import java.util.Locale;
import java.util.Map;

/** Statement classification aligned with the reference server's {@code StatementType} enum. */
public enum StatementType {
  UNKNOWN,
  CREATE,
  CLOSE,
  INSERT,
  SELECT,
  WITH,
  UPDATE,
  DELETE,
  DECLARE,
  BEGIN,
  DROP,
  END,
  ELSE,
  ELSEIF,
  CALL,
  ALTER,
  FETCH,
  FOR,
  GET,
  GOTO,
  IF,
  INCLUDE,
  ITERATE,
  LEAVE,
  LOOP,
  MERGE,
  OPEN,
  PIPE,
  REPEAT,
  RESIGNAL,
  RETURN,
  SIGNAL,
  SET,
  WHILE;

  private static final Map<String, StatementType> FROM_WORD = Map.ofEntries(
      Map.entry("CREATE", CREATE),
      Map.entry("SELECT", SELECT),
      Map.entry("WITH", WITH),
      Map.entry("INSERT", INSERT),
      Map.entry("UPDATE", UPDATE),
      Map.entry("DELETE", DELETE),
      Map.entry("DECLARE", DECLARE),
      Map.entry("DROP", DROP),
      Map.entry("END", END),
      Map.entry("ELSE", ELSE),
      Map.entry("ELSEIF", ELSEIF),
      Map.entry("CALL", CALL),
      Map.entry("BEGIN", BEGIN),
      Map.entry("ALTER", ALTER),
      Map.entry("FOR", FOR),
      Map.entry("FETCH", FETCH),
      Map.entry("GET", GET),
      Map.entry("GOTO", GOTO),
      Map.entry("IF", IF),
      Map.entry("INCLUDE", INCLUDE),
      Map.entry("ITERATE", ITERATE),
      Map.entry("LEAVE", LEAVE),
      Map.entry("LOOP", LOOP),
      Map.entry("MERGE", MERGE),
      Map.entry("PIPE", PIPE),
      Map.entry("REPEAT", REPEAT),
      Map.entry("RESIGNAL", RESIGNAL),
      Map.entry("RETURN", RETURN),
      Map.entry("SIGNAL", SIGNAL),
      Map.entry("SET", SET),
      Map.entry("WHILE", WHILE),
      Map.entry("CLOSE", CLOSE),
      Map.entry("OPEN", OPEN));

  public static StatementType fromWord(String word) {
    if (word == null) {
      return UNKNOWN;
    }
    return FROM_WORD.getOrDefault(word.toUpperCase(Locale.ROOT), UNKNOWN);
  }

  public boolean isReadOnly() {
    return this == SELECT || this == WITH;
  }
}
