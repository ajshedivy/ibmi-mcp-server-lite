package com.ibm.ibmi.mcp.server.resources;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.ToolsetConfig;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

/**
 * Builds MCP {@link McpServerFeatures.SyncResourceSpecification}s for the toolsets catalog
 * and per-toolset detail URIs. Read handlers pull the current toolset map from {@code toolsets}.
 */
public final class ToolsetsResourceRegistrar {

  private static final String MIME_JSON = "application/json";

  private ToolsetsResourceRegistrar() {}

  /** URIs that should be registered for the given toolset map (catalog + each toolset). */
  public static Set<String> desiredUris(Map<String, ToolsetConfig> toolsets) {
    Set<String> uris = new LinkedHashSet<>();
    uris.add(ToolsetsResourceLogic.CATALOG_URI);
    if (toolsets != null) {
      for (String name : toolsets.keySet()) {
        uris.add(ToolsetsResourceLogic.uriFor(name));
      }
    }
    return uris;
  }

  public static McpServerFeatures.SyncResourceSpecification catalogSpec(
      ObjectMapper mapper, Supplier<Map<String, ToolsetConfig>> toolsets) {
    Resource resource = Resource.builder()
        .uri(ToolsetsResourceLogic.CATALOG_URI)
        .name("toolsets")
        .description("Complete catalog of all available toolsets and their tools")
        .mimeType(MIME_JSON)
        .build();
    return new McpServerFeatures.SyncResourceSpecification(
        resource,
        (exchange, request) -> read(mapper, toolsets, ToolsetsResourceLogic.CATALOG_URI));
  }

  public static McpServerFeatures.SyncResourceSpecification toolsetSpec(
      String toolsetName,
      ObjectMapper mapper,
      Supplier<Map<String, ToolsetConfig>> toolsets) {
    Map<String, ToolsetConfig> current = toolsets.get();
    ToolsetConfig config = current != null ? current.get(toolsetName) : null;
    int count = config != null ? config.tools().size() : 0;
    String description = config != null
        ? ToolsetsResourceLogic.resolveDescription(config) + " (" + count + " tools)"
        : toolsetName + " toolset";

    String uri = ToolsetsResourceLogic.uriFor(toolsetName);
    Resource resource = Resource.builder()
        .uri(uri)
        .name(toolsetName)
        .description(description)
        .mimeType(MIME_JSON)
        .build();
    return new McpServerFeatures.SyncResourceSpecification(
        resource,
        (exchange, request) -> read(mapper, toolsets, uri));
  }

  /**
   * Reads catalog or per-toolset JSON. Maps logic failures to {@link McpError} so the SDK
   * returns structured JSON-RPC errors (Node parity).
   */
  public static ReadResourceResult read(
      ObjectMapper mapper,
      Supplier<Map<String, ToolsetConfig>> toolsets,
      String uri) {
    try {
      Map<String, ToolsetConfig> map = toolsets.get();
      if (map == null) {
        map = Map.of();
      }
      String filter = ToolsetsResourceLogic.toolsetNameFromUri(uri);
      Map<String, Object> payload = filter == null
          ? ToolsetsResourceLogic.buildCatalog(map)
          : ToolsetsResourceLogic.buildForToolset(map, filter);
      String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
      return new ReadResourceResult(
          List.of(new TextResourceContents(uri, MIME_JSON, json)));
    } catch (IllegalStateException e) {
      // Empty catalog (Node InitializationFailed analogue)
      throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
          .message(e.getMessage())
          .data(Map.of("totalToolsets", 0))
          .build();
    } catch (ToolsetsResourceLogic.ToolsetNotFoundException e) {
      throw McpError.RESOURCE_NOT_FOUND.apply(uri);
    } catch (IllegalArgumentException e) {
      String message = e.getMessage() != null ? e.getMessage() : "Invalid toolsets resource request";
      throw McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
          .message(message)
          .data(Map.of("uri", uri))
          .build();
    } catch (JsonProcessingException e) {
      throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
          .message("Failed to serialize toolsets resource")
          .data(Map.of("uri", uri))
          .build();
    }
  }
}
