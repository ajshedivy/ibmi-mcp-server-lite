package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;

class ToolsYamlWatcherTest {

  private ToolsYamlWatcher watcher;
  private McpServerRunner.ServerHandle handle;

  @AfterEach
  void tearDown() {
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
  @Timeout(10)
  void fileChangeTriggersReload(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Map<String, String> env = Map.of();
    Files.writeString(yaml, yamlWithTools("tool_a"));

    ToolsConfig config = new YamlConfigLoader(env).load(yaml);
    handle = McpServerRunner.start(config, Set.of());
    AtomicInteger reloadCount = new AtomicInteger();

    watcher = ToolsYamlWatcher.start(yaml, 50, () -> {
      reloadCount.incrementAndGet();
      McpServerRunner.reload(handle, yaml, env, Set.of());
    });

    Files.writeString(yaml, yamlWithTools("tool_a", "tool_b"));
    await(() -> handle.registeredTools().size() == 2, 3_000);

    assertEquals(2, handle.server().listTools().size());
    assertEquals(1, reloadCount.get());
  }

  @Test
  @Timeout(10)
  void rapidWritesCoalesceIntoFewReloads(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Map<String, String> env = Map.of();
    Files.writeString(yaml, yamlWithTools("tool_a"));

    ToolsConfig config = new YamlConfigLoader(env).load(yaml);
    handle = McpServerRunner.start(config, Set.of());
    AtomicInteger reloadCount = new AtomicInteger();

    watcher = ToolsYamlWatcher.start(yaml, 100, reloadCount::incrementAndGet);

    for (int i = 0; i < 5; i++) {
      Files.writeString(yaml, yamlWithTools("tool_a", "tool_burst_" + i));
      Thread.sleep(15);
    }
    Thread.sleep(500);

    assertTrue(reloadCount.get() < 5,
        "Expected debounce to coalesce rapid writes, got " + reloadCount.get() + " reloads");
  }

  @Test
  void closeIsIdempotent(@TempDir Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, yamlWithTools("tool_a"));
    AtomicInteger reloadCount = new AtomicInteger();

    watcher = ToolsYamlWatcher.start(yaml, 50, reloadCount::incrementAndGet);
    watcher.close();
    watcher.close();
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
