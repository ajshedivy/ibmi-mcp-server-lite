package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ibm.ibmi.mcp.config.MergeOptions;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;

class ToolsYamlWatcherTest {

  private ToolsYamlWatcher watcher;
  private McpServerRunner.ServerHandle handle;
  private final ByteArrayOutputStream stdoutCaptor = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;

  @BeforeEach
  void suppressStdout() {
    System.setOut(new PrintStream(stdoutCaptor));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    if (watcher != null) {
      watcher.close();
      watcher = null;
    }
    if (handle != null) {
      handle.close();
      handle = null;
    }
  }

  @Test
  void start_closesWatchServiceWhenParentDirectoryMissing(@TempDir Path tempDir) {
    Path missingParent = tempDir.resolve("gone").resolve("tools.yaml");
    assertThrows(IOException.class,
        () -> ToolsYamlWatcher.start(List.of(missingParent), 50, () -> {}));
  }

  @Test
  @Timeout(10)
  void fileChangeTriggersReload(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Map<String, String> env = Map.of();
    MergeOptions mergeOpts = MergeOptions.fromEnv(env);
    Files.writeString(yaml, yamlWithTools("tool_a"));

    handle = startFromYaml(yaml, env);
    AtomicInteger reloadCount = new AtomicInteger();

    watcher = ToolsYamlWatcher.start(
        List.of(yaml),
        50,
        () -> {
          reloadCount.incrementAndGet();
          McpServerRunner.reload(handle, yaml.toString(), env, mergeOpts, Set.of());
        });

    Files.writeString(yaml, yamlWithTools("tool_a", "tool_b"));
    await(() -> handle.registeredTools().size() == 2, 3_000);

    assertEquals(2, handle.server().listTools().size());
    assertEquals(1, reloadCount.get());
  }

  @Test
  @Timeout(10)
  void rapidWritesCoalesceIntoSingleReload(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    AtomicInteger reloadCount = new AtomicInteger();

    watcher = ToolsYamlWatcher.start(
        List.of(yaml), ToolsYamlWatcher.DEFAULT_DEBOUNCE_MS, reloadCount::incrementAndGet);

    for (int i = 0; i < 5; i++) {
      Files.writeString(yaml, yamlWithTools("tool_a", "tool_burst_" + i));
      Thread.sleep(20);
    }
    await(() -> reloadCount.get() >= 1, 5_000);
    int countAfterReload = reloadCount.get();
    Thread.sleep(ToolsYamlWatcher.DEFAULT_DEBOUNCE_MS + 100);

    assertEquals(1, countAfterReload,
        "Expected debounce to coalesce rapid writes into one reload");
    assertEquals(countAfterReload, reloadCount.get(),
        "Expected no additional reloads after burst settled");
  }

  @Test
  void closeIsIdempotent(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    AtomicInteger reloadCount = new AtomicInteger();

    watcher = ToolsYamlWatcher.start(List.of(yaml), 50, reloadCount::incrementAndGet);
    watcher.close();
    watcher.close();
  }

  private static McpServerRunner.ServerHandle startFromYaml(Path yaml, Map<String, String> env) {
    ToolsConfig config = new YamlConfigLoader(env).load(yaml);
    return McpServerRunner.startForTests(config, Set.of());
  }

  private static void await(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
    assertFalse(true, "Condition not met within " + timeoutMs + "ms");
  }

  private static String yamlWithTools(String... toolNames) {
    StringBuilder tools = new StringBuilder();
    for (String name : toolNames) {
      tools.append("""
            %s:
              source: ibmi-system
              description: "%s"
              statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
          """.formatted(name, name));
    }
    return """
        sources:
          ibmi-system:
            host: localhost
            user: user
            password: pass
        tools:
        """ + tools;
  }
}
