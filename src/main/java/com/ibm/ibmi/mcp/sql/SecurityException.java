package com.ibm.ibmi.mcp.sql;

/** Thrown when a SQL statement violates the effective security configuration. */
public class SecurityException extends RuntimeException {
  public SecurityException(String message) {
    super(message);
  }
}
