package com.ibm.ibmi.mcp.config;

/** Thrown when a tools YAML file is missing, malformed, or fails validation. */
public class ConfigException extends RuntimeException {
  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
