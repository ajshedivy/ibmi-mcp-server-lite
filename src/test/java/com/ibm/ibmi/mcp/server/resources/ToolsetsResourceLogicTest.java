package com.ibm.ibmi.mcp.server.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.ToolsetConfig;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

class ToolsetsResourceLogicTest {

  @Test
  void buildCatalog_emptyMapThrows() {
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> ToolsetsResourceLogic.buildCatalog(Map.of()));
    assertEquals(ToolsetsResourceLogic.EMPTY_TOOLSETS_MESSAGE, e.getMessage());
  }

  @Test
  void buildCatalog_includesSortedToolsetsAndStats() {
    Map<String, ToolsetConfig> toolsets = new LinkedHashMap<>();
    toolsets.put("zebra", new ToolsetConfig("zebra", "Z", "Zebra tools", List.of("t1")));
    toolsets.put("alpha", new ToolsetConfig(
        "alpha", null, "Alpha tools", List.of("t1", "t2")));

    Map<String, Object> catalog = ToolsetsResourceLogic.buildCatalog(toolsets);

    assertEquals(2, catalog.get("totalToolsets"));
    assertEquals(2, catalog.get("totalTools")); // unique: t1, t2
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> infos = (List<Map<String, Object>>) catalog.get("toolsets");
    assertEquals(List.of("alpha", "zebra"), infos.stream().map(i -> i.get("name")).toList());
    assertEquals("Alpha tools", infos.get(0).get("description"));
    assertEquals(List.of("t1", "t2"), infos.get(0).get("tools"));
    assertEquals(2, infos.get(0).get("toolCount"));

    @SuppressWarnings("unchecked")
    Map<String, Object> stats = (Map<String, Object>) catalog.get("statistics");
    assertEquals(List.of("t1"), stats.get("multiToolsetTools"));
    assertEquals(Map.of("alpha", 2, "zebra", 1), stats.get("toolsetCounts"));
  }

  @Test
  void buildForToolset_filtersToOneEntry() {
    Map<String, ToolsetConfig> toolsets = Map.of(
        "perf", new ToolsetConfig("perf", "Perf", "Performance", List.of("a", "b")),
        "other", new ToolsetConfig("other", null, "Other", List.of("c")));

    Map<String, Object> detail = ToolsetsResourceLogic.buildForToolset(toolsets, "perf");

    assertEquals(1, detail.get("totalToolsets"));
    assertEquals(2, detail.get("totalTools"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> infos = (List<Map<String, Object>>) detail.get("toolsets");
    assertEquals(1, infos.size());
    assertEquals("perf", infos.get(0).get("name"));
    assertEquals(List.of("a", "b"), infos.get(0).get("tools"));

    @SuppressWarnings("unchecked")
    Map<String, Object> stats = (Map<String, Object>) detail.get("statistics");
    assertEquals(Map.of("other", 1, "perf", 2), stats.get("toolsetCounts"));
  }

  @Test
  void buildForToolset_unknownThrows() {
    assertThrows(ToolsetsResourceLogic.ToolsetNotFoundException.class,
        () -> ToolsetsResourceLogic.buildForToolset(Map.of(), "missing"));
  }

  @Test
  void resolveDescription_fallsBackToTitleThenDefault() {
    assertEquals("desc", ToolsetsResourceLogic.resolveDescription(
        new ToolsetConfig("n", "title", "desc", List.of("t"))));
    assertEquals("title", ToolsetsResourceLogic.resolveDescription(
        new ToolsetConfig("n", "title", null, List.of("t"))));
    assertEquals("Tools for n", ToolsetsResourceLogic.resolveDescription(
        new ToolsetConfig("n", null, "  ", List.of("t"))));
  }

  @Test
  void toolsetNameFromUri() {
    assertNull(ToolsetsResourceLogic.toolsetNameFromUri("toolsets://"));
    assertEquals("performance", ToolsetsResourceLogic.toolsetNameFromUri("toolsets://performance"));
    assertThrows(IllegalArgumentException.class,
        () -> ToolsetsResourceLogic.toolsetNameFromUri("echo://x"));
  }

  @Test
  void uriFor_andDesiredUris() {
    assertEquals("toolsets://perf", ToolsetsResourceLogic.uriFor("perf"));
    assertEquals(
        Set.of("toolsets://", "toolsets://a", "toolsets://b"),
        ToolsetsResourceRegistrar.desiredUris(Map.of(
            "a", new ToolsetConfig("a", null, null, List.of("t")),
            "b", new ToolsetConfig("b", null, null, List.of("t")))));
  }

  @Test
  void read_emptyCatalog_throwsMcpInternalError() {
    ObjectMapper mapper = new ObjectMapper();
    McpError error = assertThrows(McpError.class,
        () -> ToolsetsResourceRegistrar.read(mapper, Map::of, ToolsetsResourceLogic.CATALOG_URI));
    assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, error.getJsonRpcError().code());
    assertTrue(error.getMessage().contains("No toolsets are currently available"));
  }

  @Test
  void read_missingToolset_throwsResourceNotFound() {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, ToolsetConfig> toolsets = Map.of(
        "perf", new ToolsetConfig("perf", null, "P", List.of("t")));
    McpError error = assertThrows(McpError.class,
        () -> ToolsetsResourceRegistrar.read(mapper, () -> toolsets, "toolsets://missing"));
    assertEquals(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, error.getJsonRpcError().code());
  }

  @Test
  void read_badUri_throwsInvalidParams() {
    ObjectMapper mapper = new ObjectMapper();
    McpError error = assertThrows(McpError.class,
        () -> ToolsetsResourceRegistrar.read(mapper, Map::of, "echo://x"));
    assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, error.getJsonRpcError().code());
  }
}
