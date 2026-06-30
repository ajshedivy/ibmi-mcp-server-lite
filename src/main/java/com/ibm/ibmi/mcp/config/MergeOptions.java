package com.ibm.ibmi.mcp.config;

import java.util.Map;

/** Env-controlled options for merging multiple tools YAML files. */
public record MergeOptions(
    boolean mergeArrays,
    boolean allowDuplicateTools,
    boolean allowDuplicateSources,
    boolean validateMerged) {

  public static MergeOptions fromEnv(Map<String, String> env) {
    return new MergeOptions(
        envFlag(env, "YAML_MERGE_ARRAYS", true),
        envFlag(env, "YAML_ALLOW_DUPLICATE_TOOLS", false),
        envFlag(env, "YAML_ALLOW_DUPLICATE_SOURCES", false),
        envFlag(env, "YAML_VALIDATE_MERGED", true));
  }

  /** Matches the reference Node transform: only literal {@code "true"} is true when set. */
  private static boolean envFlag(Map<String, String> env, String key, boolean defaultValue) {
    String value = env.get(key);
    if (value == null) {
      return defaultValue;
    }
    return "true".equals(value);
  }
}
