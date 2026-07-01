package com.ibm.ibmi.mcp.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.MergeOptions;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;
import com.ibm.ibmi.mcp.mapepire.SourceManager;
import com.ibm.ibmi.mcp.schema.JsonSchemaBuilder;
import com.ibm.ibmi.mcp.sql.SqlSecurityValidator;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import org.eclipse.jetty.server.Server;

/**
 * Builds the MCP server and registers selected YAML tools plus optional built-ins over stdio or
 * Streamable HTTP.
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
    private final ToolSpecContext toolSpecContext;
    private final ConcurrentHashMap<String, SqlToolConfig> registeredTools;
    private final boolean enableExecuteSql;
    private final boolean executeSqlReadonly;
    private volatile McpSyncServer server;
    private volatile ToolsYamlWatcher yamlWatcher;
    private volatile TestStdin testStdin;
    private volatile Server jettyServer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ServerHandle(
        SourceManager sources,
        ToolSpecContext toolSpecContext,
        ConcurrentHashMap<String, SqlToolConfig> registeredTools,
        boolean enableExecuteSql,
        boolean executeSqlReadonly) {
      this.sources = sources;
      this.toolSpecContext = toolSpecContext;
      this.registeredTools = registeredTools;
      this.enableExecuteSql = enableExecuteSql;
      this.executeSqlReadonly = executeSqlReadonly;
    }

    void attachServer(McpSyncServer server) {
      this.server = server;
    }

    void attachYamlWatcher(ToolsYamlWatcher watcher) {
      this.yamlWatcher = watcher;
    }

    void attachTestStdin(TestStdin testStdin) {
      this.testStdin = testStdin;
    }

    void attachJetty(Server jetty) {
      this.jettyServer = jetty;
    }

    public Server jettyServer() {
      return jettyServer;
    }

    public McpSyncServer server() {
      return server;
    }

    public SourceManager sources() {
      return sources;
    }

    public ToolSpecContext toolSpecContext() {
      return toolSpecContext;
    }

    public ConcurrentHashMap<String, SqlToolConfig> registeredTools() {
      return registeredTools;
    }

    boolean enableExecuteSql() {
      return enableExecuteSql;
    }

    boolean executeSqlReadonly() {
      return executeSqlReadonly;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      if (yamlWatcher != null) {
        yamlWatcher.close();
      }
      Server jetty = jettyServer;
      if (jetty != null && jetty.isRunning()) {
        try {
          jetty.stop();
        } catch (Exception e) {
          log.warn("Jetty stop failed: {}", e.getMessage());
        }
      }
      TestStdin testStdin = this.testStdin;
      if (testStdin != null) {
        testStdin.close();
      }
      McpSyncServer active = server;
      if (active != null) {
        active.close();
      }
      sources.close();
    }
  }

  /**
   * Shared mapper/schema state reused when building tool specs (initial load and hot-reload).
   */
  public record ToolSpecContext(
      ObjectMapper mapper,
      McpJsonMapper jsonMapper,
      JsonSchemaBuilder schemaBuilder,
      String outputSchema) {

    static ToolSpecContext create() {
      ObjectMapper mapper = new ObjectMapper();
      McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);
      JsonSchemaBuilder schemaBuilder = new JsonSchemaBuilder(mapper);
      return new ToolSpecContext(
          mapper, jsonMapper, schemaBuilder, schemaBuilder.buildOutputSchema());
    }
  }

  private McpServerRunner() {}

  /**
   * Starts the server with the default stdio transport (process stdin/stdout).
   * Used by {@link com.ibm.ibmi.mcp.Main}.
   */
  public static ServerHandle start(
      ToolsConfig config,
      Set<String> selectedToolsets,
      InputStream stdin,
      ServerHandle[] handleSlot,
      boolean enableExecuteSql,
      boolean executeSqlReadonly) {
    return start(config, selectedToolsets, stdin, handleSlot, System.out,
        enableExecuteSql, executeSqlReadonly);
  }

  /**
   * In-process stdio transport for unit tests (does not bind {@code System.in/out}).
   */
  static ServerHandle startForTests(ToolsConfig config, Set<String> selectedToolsets) {
    return startForTests(config, selectedToolsets, false, true);
  }

  static ServerHandle startForTests(
      ToolsConfig config,
      Set<String> selectedToolsets,
      boolean enableExecuteSql,
      boolean executeSqlReadonly) {
    Map<String, SqlToolConfig> selected = selectValidatedTools(config, selectedToolsets);
    ToolSpecContext toolSpecContext = ToolSpecContext.create();
    SourceManager sources = new SourceManager(config.sources());
    ConcurrentHashMap<String, SqlToolConfig> registeredTools = new ConcurrentHashMap<>();
    ServerHandle handle = new ServerHandle(
        sources, toolSpecContext, registeredTools, enableExecuteSql, executeSqlReadonly);

    TestStdin testStdin = new TestStdin();
    handle.attachTestStdin(testStdin);
    McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(
            toolSpecContext.jsonMapper(), testStdin, new ByteArrayOutputStream()))
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(ServerCapabilities.builder().tools(true).logging().build())
        .build();
    handle.attachServer(server);
    registerYamlTools(handle, config, selected, server);
    ensureExecuteSqlRegistered(handle, config);

    return handle;
  }

  /**
   * Starts the server with Streamable HTTP (embedded Jetty + servlet transport).
   * Used by {@link com.ibm.ibmi.mcp.Main} when {@code --transport http}.
   */
  public static ServerHandle startHttp(
      ToolsConfig config,
      Set<String> selectedToolsets,
      TransportConfig transport,
      ServerHandle[] handleSlot) throws Exception {
    return startHttp(config, selectedToolsets, transport, handleSlot, false, true);
  }

  public static ServerHandle startHttp(
      ToolsConfig config,
      Set<String> selectedToolsets,
      TransportConfig transport,
      ServerHandle[] handleSlot,
      boolean enableExecuteSql,
      boolean executeSqlReadonly) throws Exception {
    Map<String, SqlToolConfig> selected = selectValidatedTools(config, selectedToolsets);

    ToolSpecContext toolSpecContext = ToolSpecContext.create();
    SourceManager sources = new SourceManager(config.sources());
    ConcurrentHashMap<String, SqlToolConfig> registeredTools = new ConcurrentHashMap<>();
    ServerHandle handle = new ServerHandle(
        sources, toolSpecContext, registeredTools, enableExecuteSql, executeSqlReadonly);
    handleSlot[0] = handle;

    var transportProvider = HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(toolSpecContext.jsonMapper())
        .mcpEndpoint(transport.httpEndpoint())
        .build();

    McpSyncServer server = McpServer.sync(transportProvider)
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(ServerCapabilities.builder().tools(true).logging().build())
        .build();
    handle.attachServer(server);

    registerYamlTools(handle, config, selected, server);
    int toolCount = ensureExecuteSqlRegistered(handle, config);

    Server jetty = HttpTransport.start(
        transportProvider,
        transport.httpHost(),
        transport.httpPort(),
        transport.httpEndpoint());
    handle.attachJetty(jetty);

    int boundPort = HttpTransport.localPort(jetty);
    log.info("HTTP transport listening at http://{}:{}{}",
        transport.httpHost(), boundPort, transport.httpEndpoint());
    log.info("{} v{} ready on http with {} tools", SERVER_NAME, SERVER_VERSION, toolCount);
    return handle;
  }

  public static ServerHandle start(
      ToolsConfig config,
      Set<String> selectedToolsets,
      InputStream stdin,
      ServerHandle[] handleSlot,
      OutputStream stdout,
      boolean enableExecuteSql,
      boolean executeSqlReadonly) {
    Map<String, SqlToolConfig> selected = selectValidatedTools(config, selectedToolsets);

    ToolSpecContext toolSpecContext = ToolSpecContext.create();
    SourceManager sources = new SourceManager(config.sources());
    ConcurrentHashMap<String, SqlToolConfig> registeredTools = new ConcurrentHashMap<>();
    ServerHandle handle = new ServerHandle(
        sources, toolSpecContext, registeredTools, enableExecuteSql, executeSqlReadonly);
    handleSlot[0] = handle;

    McpSyncServer server = McpServer.sync(
            new StdioServerTransportProvider(toolSpecContext.jsonMapper(), stdin, stdout))
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(ServerCapabilities.builder().tools(true).logging().build())
        .build();
    handle.attachServer(server);

    registerYamlTools(handle, config, selected, server);
    int toolCount = ensureExecuteSqlRegistered(handle, config);

    log.info("{} v{} ready on stdio with {} tools", SERVER_NAME, SERVER_VERSION, toolCount);
    return handle;
  }

  static Map<String, SqlToolConfig> selectValidatedTools(
      ToolsConfig config, Set<String> selectedToolsets) {
    Map<String, SqlToolConfig> selected = config.selectTools(selectedToolsets);
    if (selected.isEmpty()) {
      log.warn("No tools selected for registration (check --toolsets / YAML contents)");
    }
    validateSelectedTools(selected.values());
    return selected;
  }

  private static void registerYamlTools(
      ServerHandle handle,
      ToolsConfig config,
      Map<String, SqlToolConfig> selected,
      McpSyncServer server) {
    for (SqlToolConfig toolConfig : selected.values()) {
      server.addTool(buildSpec(toolConfig, handle.sources(), handle.toolSpecContext()));
      handle.registeredTools().put(toolConfig.name(), toolConfig);
      log.info("Registered tool '{}' (source: {}, toolsets: {})",
          toolConfig.name(), toolConfig.source(), config.toolsetsForTool(toolConfig.name()));
    }
  }

  /**
   * Registers or updates the built-in {@code execute_sql} tool when enabled.
   *
   * @return total number of tools now registered
   */
  private static int ensureExecuteSqlRegistered(ServerHandle handle, ToolsConfig config) {
    if (!handle.enableExecuteSql()) {
      return handle.registeredTools().size();
    }

    String source = resolveExecuteSqlSource(config);
    SqlToolConfig desired = BuiltinTools.executeSql(source, handle.executeSqlReadonly());
    SqlToolConfig current = handle.registeredTools().get(BuiltinTools.EXECUTE_SQL_NAME);
    if (desired.equals(current)) {
      return handle.registeredTools().size();
    }

    validateSelectedTools(List.of(desired));
    McpSyncServer server = handle.server();
    if (current != null) {
      server.removeTool(BuiltinTools.EXECUTE_SQL_NAME);
    }
    server.addTool(buildSpec(desired, handle.sources(), handle.toolSpecContext()));
    handle.registeredTools().put(BuiltinTools.EXECUTE_SQL_NAME, desired);
    log.info("Registered built-in tool '{}' (source: {})", desired.name(), source);
    return handle.registeredTools().size();
  }

  /**
   * Picks the database source for {@code execute_sql}. When multiple sources exist, the first
   * key in YAML merge order ({@link ToolsConfig#sources()} insertion order) is used.
   */
  static String resolveExecuteSqlSource(ToolsConfig config) {
    if (config.sources().isEmpty()) {
      throw new IllegalArgumentException(
          "No sources defined; execute_sql requires a database source");
    }
    return config.sources().keySet().iterator().next();
  }

  /**
   * Watches resolved tools YAML files and hot-reloads the registry when any changes.
   *
   * <p>Under Streamable HTTP with multiple concurrent clients, reload is best-effort:
   * in-flight requests may observe the previous or updated tool set.
   */
  public static void attachYamlWatcher(
      ServerHandle handle,
      String toolsPath,
      Map<String, String> env,
      MergeOptions mergeOpts,
      Set<String> selectedToolsets) throws IOException {
    List<Path> files = YamlConfigLoader.resolveToolPaths(toolsPath);
    ToolsYamlWatcher watcher = ToolsYamlWatcher.start(
        files,
        ToolsYamlWatcher.DEFAULT_DEBOUNCE_MS,
        () -> reload(handle, toolsPath, env, mergeOpts, selectedToolsets));
    handle.attachYamlWatcher(watcher);
  }

  /**
   * Load-time security validation: fail on a statement that violates the tool's effective
   * security config ({@code readOnly} defaults to true). Shared by startup and hot-reload.
   */
  static void validateSelectedTools(Iterable<SqlToolConfig> tools) {
    for (SqlToolConfig tool : tools) {
      SqlSecurityValidator.validate(tool.statement(), tool.security());
    }
  }

  /** Builds one MCP tool spec from YAML config. Shared by startup and hot-reload. */
  static McpServerFeatures.SyncToolSpecification buildSpec(
      SqlToolConfig toolConfig,
      SourceManager sources,
      ToolSpecContext ctx) {
    Tool tool = Tool.builder()
        .name(toolConfig.name())
        .description(toolConfig.description())
        .inputSchema(ctx.jsonMapper(), ctx.schemaBuilder().buildInputSchema(toolConfig.parameters()))
        .outputSchema(ctx.jsonMapper(), ctx.outputSchema())
        .annotations(buildAnnotations(toolConfig))
        .build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(new SqlToolHandler(toolConfig, sources, ctx.mapper()))
        .build();
  }

  /**
   * Re-parses the tools YAML and updates the live MCP tool registry.
   *
   * <p>On parse/validation failure, logs to stderr and leaves the previous registration
   * untouched. Never throws to the caller (safe to invoke from a watcher thread).
   *
   * @return {@code true} when tools were updated, {@code false} when reload was skipped
   */
  public static boolean reload(
      ServerHandle handle,
      String toolsPath,
      Map<String, String> env,
      MergeOptions mergeOpts,
      Set<String> selectedToolsets) {
    try {
      ToolsConfig config = new YamlConfigLoader(env).loadAll(toolsPath, mergeOpts);
      Map<String, SqlToolConfig> selected = config.selectTools(selectedToolsets);
      validateSelectedTools(selected.values());
      validateToolSources(handle.sources(), selected);

      ToolReloadPlan plan = computeReloadPlan(handle.registeredTools(), selected);
      if (plan.toRemove().isEmpty() && plan.toAdd().isEmpty()) {
        log.debug("YAML reload: no tool changes detected");
        return false;
      }

      Map<String, SqlToolConfig> previousTools = new HashMap<>(handle.registeredTools());
      try {
        applyReloadPlan(handle, config, selected, plan);
        ensureExecuteSqlRegistered(handle, config);
        handle.server().notifyToolsListChanged();
        log.info("YAML reload applied: {} removed, {} added",
            plan.toRemove().size(), plan.toAdd().size());
        return true;
      } catch (Exception e) {
        rollbackTools(handle, previousTools);
        throw e;
      }
    } catch (Exception e) {
      log.warn("YAML reload failed; keeping previous tool registration: {}", e.getMessage());
      log.debug("YAML reload failure", e);
      return false;
    }
  }

  static void applyReloadPlan(
      ServerHandle handle,
      ToolsConfig config,
      Map<String, SqlToolConfig> selected,
      ToolReloadPlan plan) {
    for (String name : plan.toRemove()) {
      handle.server().removeTool(name);
      handle.registeredTools().remove(name);
    }
    for (String name : plan.toAdd()) {
      SqlToolConfig toolConfig = selected.get(name);
      handle.server().addTool(buildSpec(toolConfig, handle.sources(), handle.toolSpecContext()));
      handle.registeredTools().put(name, toolConfig);
      log.info("Reloaded tool '{}' (source: {}, toolsets: {})",
          toolConfig.name(), toolConfig.source(), config.toolsetsForTool(toolConfig.name()));
    }
  }

  /**
   * Restores the MCP registry and in-memory map to a prior snapshot after a failed reload.
   */
  static void rollbackTools(ServerHandle handle, Map<String, SqlToolConfig> previousTools) {
    for (String name : Set.copyOf(handle.registeredTools().keySet())) {
      if (!previousTools.containsKey(name)) {
        handle.server().removeTool(name);
        handle.registeredTools().remove(name);
      }
    }
    for (Map.Entry<String, SqlToolConfig> entry : previousTools.entrySet()) {
      String name = entry.getKey();
      SqlToolConfig previousConfig = entry.getValue();
      SqlToolConfig currentConfig = handle.registeredTools().get(name);
      if (currentConfig == null || !currentConfig.equals(previousConfig)) {
        if (currentConfig != null) {
          handle.server().removeTool(name);
        }
        handle.server().addTool(
            buildSpec(previousConfig, handle.sources(), handle.toolSpecContext()));
        handle.registeredTools().put(name, previousConfig);
      }
    }
    handle.server().notifyToolsListChanged();
    log.warn("YAML reload rolled back to previous tool registration ({} tools)",
        previousTools.size());
  }

  /** Names to remove and re-add when hot-reloading tools. */
  record ToolReloadPlan(Set<String> toRemove, Set<String> toAdd) {}

  static ToolReloadPlan computeReloadPlan(
      Map<String, SqlToolConfig> registered,
      Map<String, SqlToolConfig> selected) {
    Set<String> toRemove = new LinkedHashSet<>();
    Set<String> toAdd = new LinkedHashSet<>();

    for (Map.Entry<String, SqlToolConfig> entry : registered.entrySet()) {
      String name = entry.getKey();
      if (BuiltinTools.EXECUTE_SQL_NAME.equals(name)) {
        continue;
      }
      SqlToolConfig newConfig = selected.get(name);
      if (newConfig == null || !entry.getValue().equals(newConfig)) {
        toRemove.add(name);
      }
    }
    for (Map.Entry<String, SqlToolConfig> entry : selected.entrySet()) {
      SqlToolConfig oldConfig = registered.get(entry.getKey());
      if (oldConfig == null || !oldConfig.equals(entry.getValue())) {
        toAdd.add(entry.getKey());
      }
    }
    return new ToolReloadPlan(Set.copyOf(toRemove), Set.copyOf(toAdd));
  }

  static void validateToolSources(SourceManager sources, Map<String, SqlToolConfig> selected) {
    for (SqlToolConfig tool : selected.values()) {
      if (!sources.hasSource(tool.source())) {
        throw new IllegalArgumentException("Unknown source: " + tool.source());
      }
    }
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

  /**
   * Test-only stdin that blocks reads until closed, avoiding {@code System.in} while keeping
   * the MCP stdio transport alive until {@link ServerHandle#close()}.
   */
  private static final class TestStdin extends InputStream {

    private volatile boolean open = true;

    @Override
    public synchronized int read() throws IOException {
      while (open) {
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting for test stdin", e);
        }
      }
      return -1;
    }

    @Override
    public void close() {
      open = false;
      synchronized (this) {
        notifyAll();
      }
    }
  }
}
