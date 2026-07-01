package com.ibm.ibmi.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ibm.ibmi.mcp.config.MergeOptions;
import com.ibm.ibmi.mcp.config.ParameterConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.ToolsetConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;
import com.ibm.ibmi.mcp.server.McpServerRunner;
import com.ibm.ibmi.mcp.util.DotEnv;
import com.ibm.ibmi.mcp.util.EofNotifyingInputStream;
import com.ibm.ibmi.mcp.util.ShutdownGuard;

/**
 * Entry point. Usage:
 *
 * <pre>
 * java -jar ibmi-mcp-server-lite.jar --tools ./tools/sample-tools.yaml [--toolsets a,b]
 * java -jar ibmi-mcp-server-lite.jar --tools ./tools/ [--toolsets a,b]
 * </pre>
 *
 * The MCP protocol runs over stdio (stdout is reserved for protocol frames; all logging
 * goes to stderr). Configuration can also come from the environment:
 * {@code TOOLS_YAML_PATH}, {@code SELECTED_TOOLSETS}, {@code YAML_AUTO_RELOAD},
 * {@code MCP_LOG_LEVEL}.
 */
public final class Main {

  private static final String USAGE = """
      Usage: ibmi-mcp-server-lite --tools <file|directory|glob> [options]

      Options:
        -t,  --tools <path>       Tools YAML file, directory, or glob (env: TOOLS_YAML_PATH)
        -ts, --toolsets <a,b>     Only register tools in these toolsets (env: SELECTED_TOOLSETS)
             --list-toolsets      Print toolsets defined in the YAML file and exit
             --list-tools         Print all enabled tools defined in the YAML file and exit
             --env-file <path>    .env file for ${VAR} interpolation (default: ./.env)
             --no-reload          Disable hot-reload of tools YAML (env: YAML_AUTO_RELOAD)
             --version            Print version and exit
        -h,  --help               Show this help
      """;

  public static void main(String[] args) throws Exception {
    // Must be set before the first logger is created; slf4j-simple reads it once.
    String logLevel = System.getenv("MCP_LOG_LEVEL");
    if (logLevel != null && !logLevel.isBlank()) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", logLevel);
    }

    String toolsPath = System.getenv("TOOLS_YAML_PATH");
    String toolsetsCsv = System.getenv("SELECTED_TOOLSETS");
    String envFile = ".env";
    boolean listToolsets = false;
    boolean listTools = false;
    boolean noReload = false;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "-t", "--tools" -> toolsPath = requireValue(args, ++i, "--tools");
        case "-ts", "--toolsets" -> toolsetsCsv = requireValue(args, ++i, "--toolsets");
        case "--env-file" -> envFile = requireValue(args, ++i, "--env-file");
        case "--list-toolsets" -> listToolsets = true;
        case "--list-tools" -> listTools = true;
        case "--no-reload" -> noReload = true;
        case "--version" -> {
          System.out.println(McpServerRunner.SERVER_NAME + " " + McpServerRunner.SERVER_VERSION);
          return;
        }
        case "-h", "--help" -> {
          System.out.println(USAGE);
          return;
        }
        default -> fail("Unknown option: " + args[i]);
      }
    }

    if (toolsPath == null || toolsPath.isBlank()) {
      fail("No tools YAML path given (use --tools or TOOLS_YAML_PATH)");
    }

    Map<String, String> env = DotEnv.environment(Path.of(envFile));
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    ToolsConfig config;
    try {
      config = new YamlConfigLoader(env).loadAll(toolsPath, mergeOpts);
    } catch (com.ibm.ibmi.mcp.config.ConfigException e) {
      fail(e.getMessage());
      return;
    }

    if (listToolsets) {
      printToolsets(config);
      return;
    }

    if (listTools) { 
      printTools(config); 
      return; 
    }

    Set<String> selected = new LinkedHashSet<>();
    if (toolsetsCsv != null && !toolsetsCsv.isBlank()) {
      Arrays.stream(toolsetsCsv.split(",")).map(String::trim)
          .filter(s -> !s.isEmpty()).forEach(selected::add);
    }

    boolean yamlAutoReload = resolveYamlAutoReload(env, noReload);
    CountDownLatch shutdownLatch = new CountDownLatch(1);
    AtomicBoolean shuttingDown = new AtomicBoolean(false);
    McpServerRunner.ServerHandle[] handleSlot = new McpServerRunner.ServerHandle[1];

    Runnable shutdown = ShutdownGuard.once(shuttingDown, shutdownLatch, () -> {
      if (handleSlot[0] != null) {
        handleSlot[0].close();
      }
    });

    InputStream stdin = new EofNotifyingInputStream(
        System.in, () -> new Thread(shutdown, "stdin-eof").start());
    Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "shutdown-cleanup"));

    handleSlot[0] = McpServerRunner.start(config, selected, stdin, handleSlot);

    if (yamlAutoReload) {
      try {
        McpServerRunner.attachYamlWatcher(handleSlot[0], toolsPath, env, mergeOpts, selected);
      } catch (IOException e) {
        System.err.println("warning: could not start YAML watcher: " + e.getMessage());
      }
    }

    shutdownLatch.await();
  }

  private static void printToolsets(ToolsConfig config) {
    if (config.toolsets().isEmpty()) {
      System.out.println("No toolsets defined.");
      return;
    }
    for (ToolsetConfig ts : config.toolsets().values()) {
      System.out.printf("%s (%d tools)%s%n", ts.name(), ts.tools().size(),
          ts.title() != null ? " — " + ts.title() : "");
      ts.tools().forEach(tool -> System.out.println("  - " + tool));
    }
  }

  private static void printTools(ToolsConfig config) {
    if (config.tools().isEmpty()) {
      System.out.println("No tools enabled.");
      return;
    }
    for (SqlToolConfig tool : config.tools().values()) {
      if (!tool.enabled()) continue;
      System.out.printf("%s - %d parameters - %s%n", tool.name(), tool.parameters().size(), tool.description());
      List<String> toolsets = config.toolsetsForTool(tool.name());
      if (toolsets.isEmpty()) {
        System.out.println("  toolsets: -");
      } else {
        System.out.println("  toolsets: " + String.join(", ", toolsets));
      }
      System.out.print(formatParameters(tool.parameters()));
    }
  }

  private static String formatParameters(List<ParameterConfig> parameters) {
    if (parameters.isEmpty()) {
     return "  (no parameters)\n";
    }
    StringBuilder sb = new StringBuilder();
    for (ParameterConfig p : parameters) {
      sb.append("  ").append(p.name()).append(" (").append(p.type()).append(")");
      if (p.isRequiredInSchema()) sb.append(" [required]");
      if (p.defaultValue() != null) sb.append(" default: ").append(p.defaultValue());
      if (p.enumValues() != null && !p.enumValues().isEmpty()) sb.append(" choices: " + String.join(", ", p.enumValues().stream().map(Object::toString).toList()));
      if (p.description() != null) sb.append(" — ").append(p.description());
      sb.append("\n");
    }
    return sb.toString();
  }

  private static String requireValue(String[] args, int index, String option) {
    if (index >= args.length) {
      fail("Missing value for " + option);
    }
    return args[index];
  }

  /**
   * Whether to watch the tools YAML and hot-reload on change.
   *
   * <p>Resolution order: {@code --no-reload} disables reload; otherwise read
   * {@code YAML_AUTO_RELOAD} from the merged environment ({@link DotEnv#environment}
   * — process env wins over the {@code .env} file). Enabled when unset (reference
   * default), or when the value is {@code true} or {@code 1}.
   */
  static boolean resolveYamlAutoReload(Map<String, String> env, boolean noReloadCli) {
    if (noReloadCli) {
      return false;
    }
    String value = env.get("YAML_AUTO_RELOAD");
    if (value == null || value.isBlank()) {
      return true;
    }
    return "true".equalsIgnoreCase(value) || "1".equals(value);
  }

  private static void fail(String message) {
    System.err.println("error: " + message);
    System.err.println(USAGE);
    System.exit(2);
  }
}
