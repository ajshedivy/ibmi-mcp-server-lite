package com.ibm.ibmi.mcp.mapepire;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.config.SourceConfig;

import io.github.mapepire_ibmi.Pool;
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

    var getPoolFinished = new AtomicBoolean(false);
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

  @Test
  void closeContinuesWhenOnePoolEndFails() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE, Map.of());
    PoolOptions options = SourceManager.poolOptionsFor(source);

    AtomicBoolean secondPoolEnded = new AtomicBoolean(false);
    Pool failingPool = new Pool(options) {
      @Override
      public void end() {
        throw new RuntimeException("pool end failed");
      }
    };
    Pool succeedingPool = new Pool(options) {
      @Override
      public void end() {
        secondPoolEnded.set(true);
      }
    };

    SourceManager manager = new SourceManager(Map.of());
    registerPool(manager, "failing", failingPool);
    registerPool(manager, "succeeding", succeedingPool);

    assertDoesNotThrow(() -> manager.close(Duration.ZERO));
    assertTrue(secondPoolEnded.get(), "remaining pools should still be closed");
  }

  @Test
  void getHealthSummary_emptyWhenNoPools() {
    SourceManager manager = new SourceManager(Map.of());
    assertEquals(Map.of(), manager.getHealthSummary());
  }

  @Test
  void getHealthSummary_reportsHealthyPools() {
    Instant activity = Instant.parse("2026-07-29T15:00:00Z");
    SourceManager manager = new SourceManager(Map.of());
    manager.putHealth("src-a", new SourceManager.PoolHealth(true, false, "healthy", activity));
    manager.putHealth("src-b", new SourceManager.PoolHealth(true, false, "healthy", activity));

    Map<String, Map<String, Object>> summary = manager.getHealthSummary();
    assertEquals(2, summary.size());
    assertEquals("healthy", summary.get("src-a").get("healthStatus"));
    assertEquals(true, summary.get("src-a").get("initialized"));
    assertEquals(false, summary.get("src-a").get("connecting"));
    assertEquals(activity.toString(), summary.get("src-a").get("lastActivityAt"));
    assertEquals("healthy", summary.get("src-b").get("healthStatus"));
  }

  @Test
  void getHealthSummary_reportsUnhealthyPools() {
    SourceManager manager = new SourceManager(Map.of());
    manager.putHealth("good", new SourceManager.PoolHealth(true, false, "healthy", Instant.now()));
    manager.putHealth("bad", new SourceManager.PoolHealth(false, false, "unhealthy", Instant.now()));

    Map<String, Map<String, Object>> summary = manager.getHealthSummary();
    assertEquals("healthy", summary.get("good").get("healthStatus"));
    assertEquals("unhealthy", summary.get("bad").get("healthStatus"));
    assertEquals(false, summary.get("bad").get("initialized"));
  }

  @Test
  void markConnecting_firstConnectUsesUnknown() {
    SourceManager manager = new SourceManager(Map.of());
    manager.markConnecting("ibmi");

    Map<String, Object> pool = manager.getHealthSummary().get("ibmi");
    assertEquals("unknown", pool.get("healthStatus"));
    assertEquals(true, pool.get("connecting"));
    assertEquals(false, pool.get("initialized"));
  }

  @Test
  void markConnecting_keepsUnhealthyStickyDuringReconnect() {
    Instant prior = Instant.parse("2026-07-29T12:00:00Z");
    SourceManager manager = new SourceManager(Map.of());
    manager.putHealth("ibmi", new SourceManager.PoolHealth(false, false, "unhealthy", prior));

    manager.markConnecting("ibmi");

    Map<String, Object> pool = manager.getHealthSummary().get("ibmi");
    assertEquals("unhealthy", pool.get("healthStatus"));
    assertEquals(true, pool.get("connecting"));
    assertEquals(false, pool.get("initialized"));
    assertTrue(((String) pool.get("lastActivityAt")).compareTo(prior.toString()) >= 0);
  }

  @Test
  void recordActivity_updatesTimestampAndMarksHealthy() throws InterruptedException {
    Instant prior = Instant.parse("2026-07-29T12:00:00Z");
    SourceManager manager = new SourceManager(Map.of());
    manager.putHealth("ibmi", new SourceManager.PoolHealth(true, false, "healthy", prior));

    Thread.sleep(2);
    manager.recordActivity("ibmi");

    Map<String, Object> pool = manager.getHealthSummary().get("ibmi");
    assertEquals("healthy", pool.get("healthStatus"));
    assertEquals(true, pool.get("initialized"));
    assertTrue(((String) pool.get("lastActivityAt")).compareTo(prior.toString()) > 0);
  }

  @Test
  void recordActivity_noopWhenSourceUnknown() {
    SourceManager manager = new SourceManager(Map.of());
    manager.recordActivity("missing");
    assertEquals(Map.of(), manager.getHealthSummary());
  }

  @SuppressWarnings("unchecked")
  private static void registerPool(SourceManager manager, String name, Pool pool) throws Exception {
    Field poolsField = SourceManager.class.getDeclaredField("pools");
    poolsField.setAccessible(true);
    Map<String, Pool> pools = (Map<String, Pool>) poolsField.get(manager);
    pools.put(name, pool);
  }
}
