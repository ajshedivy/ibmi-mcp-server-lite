package com.ibm.ibmi.mcp.mapepire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.config.SourceConfig;

import io.github.mapepire_ibmi.types.DaemonServer;
import io.github.mapepire_ibmi.types.PoolOptions;

class SourceManagerTest {

  @Test
  void unknownSourceThrowsBeforeConnecting() {
    SourceManager manager = new SourceManager(Map.of());
    assertThrows(IllegalArgumentException.class, () -> manager.getPool("missing"));
  }

  @Test
  void poolOptionsUseSourceDefaults() {
    SourceConfig source = new SourceConfig(
        "ibmi", "host.example.com", 8076, "user", "pass", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE, Map.of());
    PoolOptions options = SourceManager.poolOptionsFor(source);

    assertEquals(SourceConfig.DEFAULT_MAX_SIZE, options.getMaxSize());
    assertEquals(SourceConfig.DEFAULT_STARTING_SIZE, options.getStartingSize());

    DaemonServer server = options.getCreds();
    assertEquals("host.example.com", server.getHost());
    assertEquals(8076, server.getPort());
    assertEquals("user", server.getUser());
    assertEquals("pass", server.getPassword());
    assertEquals(true, server.getRejectUnauthorized());
  }

  @Test
  void poolOptionsHonorExplicitSizes() {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", true,
        5, 1, Map.of());
    PoolOptions options = SourceManager.poolOptionsFor(source);

    assertEquals(5, options.getMaxSize());
    assertEquals(1, options.getStartingSize());
    assertEquals(false, options.getCreds().getRejectUnauthorized());
  }
}
