package com.ibm.ibmi.mcp.server;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.mapepire.SourceManager;
import com.ibm.ibmi.mcp.schema.JsonSchemaBuilder;
import com.ibm.ibmi.mcp.sql.SqlSecurityValidator;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * Builds the MCP server over stdio and registers every selected YAML tool.
 *
 * <p>TODO: Streamable HTTP transport
 * ({@code HttpServletStreamableServerTransportProvider} in mcp-core) behind a
 * {@code --transport http} flag, mirroring the reference server.
 */
public final class McpServerRunner {

  private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);

  public static final String SERVER_NAME = "ibmi-mcp-server-lite";
  public static final String SERVER_VERSION = "0.1.0";

  /**
   * Holds the connection manager and (once built) the MCP server so both can be closed on exit.
   * Published to {@code handleSlot} before the stdio transport starts reading stdin.
   */
  public static final class ServerHandle implements AutoCloseable {

    private final SourceManager sources;
    private volatile McpSyncServer server;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ServerHandle(SourceManager sources) {
      this.sources = sources;
    }

    void attachServer(McpSyncServer server) {
      this.server = server;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      sources.close();
      McpSyncServer active = server;
      if (active != null) {
        active.close();
      }
    }
  }

  private McpServerRunner() {}

  public static ServerHandle start(ToolsConfig config, Set<String> selectedToolsets, InputStream stdin,
      ServerHandle[] handleSlot) {
    ObjectMapper mapper = new ObjectMapper();
    McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);
    JsonSchemaBuilder schemaBuilder = new JsonSchemaBuilder(mapper);
    SourceManager sources = new SourceManager(config.sources());
    ServerHandle handle = new ServerHandle(sources);
    handleSlot[0] = handle;

    Map<String, SqlToolConfig> selected = config.selectTools(selectedToolsets);
    if (selected.isEmpty()) {
      log.warn("No tools selected for registration (check --toolsets / YAML contents)");
    }

    // Load-time security validation: fail startup on a statement that violates the
    // tool's effective security config (readOnly defaults to true).
    for (SqlToolConfig tool : selected.values()) {
      SqlSecurityValidator.validate(tool.statement(), tool.security());
    }

    // stdin EOF is detected by EofNotifyingInputStream in Main; transport remains the sole reader.
    McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(jsonMapper, stdin, System.out))
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(ServerCapabilities.builder().tools(true).logging().build())
        .build();
    handle.attachServer(server);

    String outputSchema = schemaBuilder.buildOutputSchema();

    for (SqlToolConfig toolConfig : selected.values()) {
      Tool tool = Tool.builder()
          .name(toolConfig.name())
          .description(toolConfig.description())
          .inputSchema(jsonMapper, schemaBuilder.buildInputSchema(toolConfig.parameters()))
          .outputSchema(jsonMapper, outputSchema)
          .annotations(buildAnnotations(toolConfig))
          .build();
      McpServerFeatures.SyncToolSpecification spec =
          McpServerFeatures.SyncToolSpecification.builder()
              .tool(tool)
              .callHandler(new SqlToolHandler(toolConfig, sources, mapper))
              .build();
      server.addTool(spec);
      log.info("Registered tool '{}' (source: {}, toolsets: {})",
          toolConfig.name(), toolConfig.source(), config.toolsetsForTool(toolConfig.name()));
    }

    log.info("{} v{} ready on stdio with {} tools", SERVER_NAME, SERVER_VERSION, selected.size());
    return handle;
  }

  /**
   * MCP tool annotations. Resolution order mirrors the reference server:
   * {@code annotations.readOnlyHint ?? security.readOnly ?? true}; title falls back to a
   * human-readable form of the tool name. Custom annotation keys (domain, category,
   * toolsets) are not representable in the Java SDK's typed ToolAnnotations record.
   */
  private static ToolAnnotations buildAnnotations(SqlToolConfig tool) {
    Map<String, Object> ann = tool.annotations();
    Boolean readOnly = ann.get("readOnlyHint") instanceof Boolean b ? b
        : tool.security().readOnly() != null ? tool.security().readOnly()
        : Boolean.TRUE;
    return new ToolAnnotations(
        ann.get("title") instanceof String s ? s : formatToolTitle(tool.name()),
        readOnly,
        ann.get("destructiveHint") instanceof Boolean b ? b : null,
        ann.get("idempotentHint") instanceof Boolean b ? b : null,
        ann.get("openWorldHint") instanceof Boolean b ? b : null,
        null);
  }

  /** {@code active_job_info} → {@code Active Job Info}. */
  static String formatToolTitle(String name) {
    String[] words = name.split("[_-]");
    StringBuilder title = new StringBuilder();
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if (title.length() > 0) {
        title.append(' ');
      }
      title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return title.toString();
  }
}
