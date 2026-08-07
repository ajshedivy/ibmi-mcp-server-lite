package com.ibm.ibmi.mcp.server.resources;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.ibm.ibmi.mcp.config.ToolsetConfig;

/**
 * Builds the Node-shaped toolsets resource JSON payload from YAML toolset configs.
 * No MCP types — registration wraps the result for {@code resources/read}.
 */
public final class ToolsetsResourceLogic {

  public static final String CATALOG_URI = "toolsets://";
  public static final String URI_PREFIX = "toolsets://";

  /** Message when the catalog is read with an empty toolset map (Node parity). */
  public static final String EMPTY_TOOLSETS_MESSAGE =
      "No toolsets are currently available. The toolset system may not be initialized.";

  private ToolsetsResourceLogic() {}

  /** Resource URI for a named toolset ({@code toolsets://{name}}). */
  public static String uriFor(String toolsetName) {
    return URI_PREFIX + toolsetName;
  }

  /**
   * Parses the toolset name from a resource URI, or {@code null} for the catalog
   * ({@link #CATALOG_URI}).
   *
   * @throws IllegalArgumentException when the URI is not under {@code toolsets://}
   */
  public static String toolsetNameFromUri(String uri) {
    if (uri == null || uri.isBlank()) {
      throw new IllegalArgumentException("Resource URI must not be blank");
    }
    if (CATALOG_URI.equals(uri) || (URI_PREFIX + "/").equals(uri)) {
      return null;
    }
    if (!uri.startsWith(URI_PREFIX)) {
      throw new IllegalArgumentException("Unsupported resource URI: " + uri);
    }
    String name = uri.substring(URI_PREFIX.length());
    if (name.isEmpty() || name.startsWith("/")) {
      return null;
    }
    return name;
  }

  /**
   * Full catalog for {@link #CATALOG_URI}.
   *
   * @throws IllegalStateException when {@code toolsets} is null or empty (Node parity)
   */
  public static Map<String, Object> buildCatalog(Map<String, ToolsetConfig> toolsets) {
    Map<String, ToolsetConfig> source = toolsets == null ? Map.of() : toolsets;
    if (source.isEmpty()) {
      throw new IllegalStateException(EMPTY_TOOLSETS_MESSAGE);
    }
    return build(source, null);
  }

  /**
   * Detail payload for one toolset (same schema as the catalog, with a one-element
   * {@code toolsets} array).
   *
   * @throws IllegalArgumentException when {@code toolsetName} is blank
   * @throws ToolsetNotFoundException when {@code toolsetName} is missing from the map
   */
  public static Map<String, Object> buildForToolset(
      Map<String, ToolsetConfig> toolsets, String toolsetName) {
    if (toolsetName == null || toolsetName.isBlank()) {
      throw new IllegalArgumentException("Toolset name must not be blank");
    }
    if (toolsets == null || !toolsets.containsKey(toolsetName)) {
      throw new ToolsetNotFoundException(toolsetName);
    }
    return build(toolsets, toolsetName);
  }

  /** Thrown when a per-toolset resource URI names an unknown toolset. */
  public static final class ToolsetNotFoundException extends IllegalArgumentException {
    public ToolsetNotFoundException(String toolsetName) {
      super("Toolset '" + toolsetName + "' not found");
    }
  }

  /**
   * Builds catalog or filtered detail from {@code toolsets}. When {@code filterName} is
   * non-null, only that toolset is included. Caller must ensure {@code toolsets} is
   * non-empty for catalog reads.
   */
  static Map<String, Object> build(Map<String, ToolsetConfig> toolsets, String filterName) {
    Map<String, ToolsetConfig> source = toolsets;

    List<String> names = new ArrayList<>(source.keySet());
    names.sort(Comparator.naturalOrder());

    List<Map<String, Object>> toolsetInfos = new ArrayList<>();
    Map<String, Integer> toolsetCounts = new TreeMap<>();
    Map<String, Integer> membershipCounts = new HashMap<>();

    for (String name : names) {
      if (filterName != null && !filterName.equals(name)) {
        continue;
      }
      ToolsetConfig ts = source.get(name);
      if (ts == null) {
        continue;
      }
      List<String> tools = List.copyOf(ts.tools());
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("name", ts.name());
      info.put("description", resolveDescription(ts));
      info.put("tools", tools);
      info.put("toolCount", tools.size());
      toolsetInfos.add(info);
      toolsetCounts.put(ts.name(), tools.size());
      for (String tool : tools) {
        membershipCounts.merge(tool, 1, Integer::sum);
      }
    }

    List<String> multiToolsetTools = membershipCounts.entrySet().stream()
        .filter(e -> e.getValue() > 1)
        .map(Map.Entry::getKey)
        .sorted()
        .toList();

    // For a filtered read, statistics still reflect the full map (Node parity via
    // ToolsetManager stats). Counts / multi membership use the full source.
    Map<String, Integer> fullCounts = new TreeMap<>();
    Map<String, Integer> fullMembership = new HashMap<>();
    Set<String> allUnique = new LinkedHashSet<>();
    for (ToolsetConfig ts : source.values()) {
      fullCounts.put(ts.name(), ts.tools().size());
      for (String tool : ts.tools()) {
        allUnique.add(tool);
        fullMembership.merge(tool, 1, Integer::sum);
      }
    }
    List<String> fullMulti = fullMembership.entrySet().stream()
        .filter(e -> e.getValue() > 1)
        .map(Map.Entry::getKey)
        .sorted()
        .toList();

    int totalToolsets = filterName != null ? toolsetInfos.size() : source.size();
    int totalTools = filterName != null
        ? toolsetInfos.stream().mapToInt(i -> (Integer) i.get("toolCount")).sum()
        : allUnique.size();

    Map<String, Object> statistics = new LinkedHashMap<>();
    statistics.put("multiToolsetTools", filterName != null ? fullMulti : multiToolsetTools);
    statistics.put("toolsetCounts", filterName != null ? fullCounts : toolsetCounts);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("totalToolsets", totalToolsets);
    response.put("totalTools", totalTools);
    response.put("toolsets", toolsetInfos);
    response.put("statistics", statistics);
    response.put("timestamp", Instant.now().toString());
    return response;
  }

  public static String resolveDescription(ToolsetConfig ts) {
    if (ts.description() != null && !ts.description().isBlank()) {
      return ts.description();
    }
    if (ts.title() != null && !ts.title().isBlank()) {
      return ts.title();
    }
    return "Tools for " + ts.name();
  }
}
