package com.ibm.ibmi.mcp.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsed and validated contents of a tools YAML file. */
public record ToolsConfig(
    Map<String, SourceConfig> sources,
    Map<String, SqlToolConfig> tools,
    Map<String, ToolsetConfig> toolsets) {

  /** Names of every toolset that contains the given tool. */
  public List<String> toolsetsForTool(String toolName) {
    return toolsets.values().stream()
        .filter(ts -> ts.tools().contains(toolName))
        .map(ToolsetConfig::name)
        .toList();
  }

  /**
   * Tools to register, honoring {@code enabled: false} and an optional toolset selection.
   * Mirrors the reference implementation: when a selection is active, tools that belong
   * to no selected toolset (including tools in no toolset at all) are dropped.
   */
  public Map<String, SqlToolConfig> selectTools(Set<String> selectedToolsets) {
    Map<String, SqlToolConfig> result = new LinkedHashMap<>();
    for (var entry : tools.entrySet()) {
      SqlToolConfig tool = entry.getValue();
      if (!tool.enabled()) {
        continue;
      }
      if (selectedToolsets != null && !selectedToolsets.isEmpty()) {
        Set<String> membership = new LinkedHashSet<>(toolsetsForTool(entry.getKey()));
        membership.retainAll(selectedToolsets);
        if (membership.isEmpty()) {
          continue;
        }
      }
      result.put(entry.getKey(), tool);
    }
    return result;
  }
}
