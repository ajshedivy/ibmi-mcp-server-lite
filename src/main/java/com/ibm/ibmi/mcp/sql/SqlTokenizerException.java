package com.ibm.ibmi.mcp.sql;

/** Thrown when SQL cannot be tokenized (e.g. unclosed string or comment). */
public class SqlTokenizerException extends RuntimeException {
  public SqlTokenizerException(String message) {
    super(message);
  }
}
