package com.ibm.ibmi.mcp.mapepire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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

  @Test
  void closeProceedsAfterGraceWhenQueryStillInFlight() {
    SourceManager manager = new SourceManager(Map.of());
    manager.beginQuery();
    long start = System.nanoTime();
    manager.close(Duration.ofMillis(100));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 90);
    manager.endQuery();
  }

  @Test
  void closeReturnsImmediatelyWhenNoInFlightQueries() {
    SourceManager manager = new SourceManager(Map.of());
    long start = System.nanoTime();
    manager.close(Duration.ofSeconds(5));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500);
  }

  @Test
  void closeDoesNotHoldPoolLockWhileAwaitingInFlight() throws InterruptedException {
    SourceManager manager = new SourceManager(Map.of());
    manager.beginQuery();

    var getPoolFinished = new java.util.concurrent.atomic.AtomicBoolean(false);
    Thread getPoolThread = new Thread(() -> {
      try {
        manager.getPool("missing");
      } catch (IllegalArgumentException expected) {
        // unknown source — only need to verify the lock was obtainable
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        getPoolFinished.set(true);
      }
    });

    Thread shutdownThread = new Thread(() -> manager.close(Duration.ofMillis(300)));

    long start = System.nanoTime();
    getPoolThread.start();
    Thread.sleep(30);
    shutdownThread.start();
    getPoolThread.join(1000);

    assertTrue(getPoolFinished.get(), "getPool should complete while close awaits in-flight work");
    assertTrue(
        Duration.ofNanos(System.nanoTime() - start).toMillis() < 200,
        "getPool should not block for the full shutdown grace period");
    manager.endQuery();
    shutdownThread.join(2000);
  }
}
