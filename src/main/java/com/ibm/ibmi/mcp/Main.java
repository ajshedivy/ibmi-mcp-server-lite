package com.ibm.ibmi.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.ToolsetConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;
import com.ibm.ibmi.mcp.server.McpServerRunner;
import com.ibm.ibmi.mcp.util.DotEnv;

/**
 * Entry point. Usage:
 *
 * <pre>
 * java -jar ibmi-mcp-server-lite.jar --tools ./tools/sample-tools.yaml [--toolsets a,b]
 * </pre>
 *
 * The MCP protocol runs over stdio (stdout is reserved for protocol frames; all logging
 * goes to stderr). Configuration can also come from the environment:
 * {@code TOOLS_YAML_PATH}, {@code SELECTED_TOOLSETS}, {@code MCP_LOG_LEVEL}.
 */
public final class Main {

  private static final String USAGE = """
      Usage: ibmi-mcp-server-lite --tools <tools.yaml> [options]

      Options:
        -t,  --tools <path>       Tools YAML file (env: TOOLS_YAML_PATH)
        -ts, --toolsets <a,b>     Only register tools in these toolsets (env: SELECTED_TOOLSETS)
             --list-toolsets      Print toolsets defined in the YAML file and exit
             --env-file <path>    .env file for ${VAR} interpolation (default: ./.env)
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

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "-t", "--tools" -> toolsPath = requireValue(args, ++i, "--tools");
        case "-ts", "--toolsets" -> toolsetsCsv = requireValue(args, ++i, "--toolsets");
        case "--env-file" -> envFile = requireValue(args, ++i, "--env-file");
        case "--list-toolsets" -> listToolsets = true;
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
      fail("No tools YAML file given (use --tools or TOOLS_YAML_PATH)");
    }
    Path tools = Path.of(toolsPath);
    if (!Files.isRegularFile(tools)) {
      fail("Tools YAML file not found: " + tools.toAbsolutePath());
    }

    Map<String, String> env = DotEnv.environment(Path.of(envFile));
    ToolsConfig config = new YamlConfigLoader(env).load(tools);

    if (listToolsets) {
      printToolsets(config);
      return;
    }

    Set<String> selected = new LinkedHashSet<>();
    if (toolsetsCsv != null && !toolsetsCsv.isBlank()) {
      Arrays.stream(toolsetsCsv.split(",")).map(String::trim)
          .filter(s -> !s.isEmpty()).forEach(selected::add);
    }

    McpServerRunner.ServerHandle handle = McpServerRunner.start(config, selected);
    Runtime.getRuntime().addShutdownHook(new Thread(handle::close, "shutdown-cleanup"));

    // The stdio transport serves on background threads; keep the process alive until the
    // client kills it (standard lifecycle for stdio MCP servers).
    new CountDownLatch(1).await();
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

  private static String requireValue(String[] args, int index, String option) {
    if (index >= args.length) {
      fail("Missing value for " + option);
    }
    return args[index];
  }

  private static void fail(String message) {
    System.err.println("error: " + message);
    System.err.println(USAGE);
    System.exit(2);
  }
}
