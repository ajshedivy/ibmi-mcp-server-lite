package com.ibm.ibmi.mcp.mapepire;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
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
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE, SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
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
        5, 1, SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    PoolOptions options = SourceManager.poolOptionsFor(source);

    assertEquals(5, options.getMaxSize());
    assertEquals(1, options.getStartingSize());
    assertEquals(false, options.getCreds().getRejectUnauthorized());
  }

  @Test
  void computeReloadPlanDiffsAddedUpdatedAndRemovedSources() {
    SourceConfig unchanged = source("unchanged", "same.example.com");
    SourceConfig oldUpdated = source("updated", "old.example.com");
    SourceConfig newUpdated = source("updated", "new.example.com");
    SourceConfig removed = source("removed", "removed.example.com");
    SourceConfig added = source("added", "added.example.com");

    SourceManager.SourceReloadPlan plan = SourceManager.computeReloadPlan(
        Map.of(
            "unchanged", unchanged,
            "updated", oldUpdated,
            "removed", removed),
        Map.of(
            "unchanged", unchanged,
            "updated", newUpdated,
            "added", added));

    assertEquals(Set.of("added"), plan.added());
    assertEquals(Set.of("updated"), plan.updated());
    assertEquals(Set.of("removed"), plan.removed());
    assertFalse(plan.isEmpty());
  }

  @Test
  void applyReloadPlanKeepsUnchangedPoolAndClosesPasswordUpdatedAndRemovedPools()
      throws Exception {
    SourceConfig oldUpdated = source("updated", "old.example.com");
    SourceConfig newUpdated = source("updated", "new.example.com", "replacement-pass");
    SourceConfig unchanged = source("unchanged", "same.example.com");
    SourceConfig removed = source("removed", "removed.example.com");
    SourceConfig added = source("added", "added.example.com");
    Map<String, SourceConfig> initial = new LinkedHashMap<>();
    initial.put("updated", oldUpdated);
    initial.put("unchanged", unchanged);
    initial.put("removed", removed);
    SourceManager manager = new SourceManager(initial);

    AtomicBoolean updatedEnded = new AtomicBoolean(false);
    AtomicBoolean unchangedEnded = new AtomicBoolean(false);
    AtomicBoolean removedEnded = new AtomicBoolean(false);
    Pool updatedPool = trackingPool(oldUpdated, updatedEnded);
    Pool unchangedPool = trackingPool(unchanged, unchangedEnded);
    Pool removedPool = trackingPool(removed, removedEnded);
    registerPool(manager, "updated", updatedPool);
    registerPool(manager, "unchanged", unchangedPool);
    registerPool(manager, "removed", removedPool);
    manager.putHealth(
        "removed", new SourceManager.PoolHealth(true, false, "healthy", Instant.now()));

    Map<String, SourceConfig> desired = new LinkedHashMap<>();
    desired.put("added", added);
    desired.put("unchanged", unchanged);
    desired.put("updated", newUpdated);
    manager.applyReloadPlan(
        SourceManager.computeReloadPlan(manager.snapshotConfigs(), desired),
        Duration.ZERO);

    assertTrue(updatedEnded.get());
    assertTrue(removedEnded.get());
    assertFalse(unchangedEnded.get());
    assertSame(unchangedPool, poolMap(manager).get("unchanged"));
    assertFalse(poolMap(manager).containsKey("updated"));
    assertFalse(poolMap(manager).containsKey("removed"));
    assertEquals(desired, manager.snapshotConfigs());
    assertEquals(
        java.util.List.of("added", "unchanged", "updated"),
        java.util.List.copyOf(manager.snapshotConfigs().keySet()));
    assertTrue(manager.hasSource("added"));
    assertFalse(manager.hasSource("removed"));
    assertFalse(manager.getHealthSummary().containsKey("removed"));

    manager.close(Duration.ZERO);
  }

  @Test
  void applyReloadPlanWaitsForAffectedQueriesWithoutBlockingUnchangedSources() throws Exception {
    SourceConfig updated = source("updated", "old.example.com");
    SourceConfig unchanged = source("unchanged", "same.example.com");
    SourceManager manager =
        new SourceManager(Map.of("updated", updated, "unchanged", unchanged));
    AtomicBoolean updatedEnded = new AtomicBoolean(false);
    registerPool(manager, "updated", trackingPool(updated, updatedEnded));
    manager.beginQuery("updated");

    SourceConfig replacement = source("updated", "new.example.com");
    Thread reload = new Thread(() -> manager.applyReloadPlan(
        SourceManager.computeReloadPlan(
            manager.snapshotConfigs(),
            Map.of("updated", replacement, "unchanged", unchanged)),
        Duration.ofSeconds(1)));
    reload.start();
    Thread.sleep(50);

    assertTrue(reload.isAlive(), "reload should wait for the affected in-flight query");
    assertFalse(updatedEnded.get());
    manager.beginQuery("unchanged");
    manager.endQuery("unchanged");

    manager.endQuery("updated");
    reload.join(2000);
    assertFalse(reload.isAlive());
    assertTrue(updatedEnded.get());
    manager.close(Duration.ZERO);
  }

  @Test
  void restoreConfigsClosesPoolsFromReplacementConfigAndRestoresSnapshot() throws Exception {
    SourceConfig original = source("ibmi", "old.example.com");
    SourceConfig replacement = source("ibmi", "new.example.com");
    SourceConfig added = source("added", "added.example.com");
    SourceManager manager = new SourceManager(Map.of("ibmi", original));
    Map<String, SourceConfig> snapshot = manager.snapshotConfigs();

    manager.applyReloadPlan(SourceManager.computeReloadPlan(
        snapshot, Map.of("ibmi", replacement, "added", added)), Duration.ZERO);

    AtomicBoolean replacementEnded = new AtomicBoolean(false);
    AtomicBoolean addedEnded = new AtomicBoolean(false);
    registerPool(manager, "ibmi", trackingPool(replacement, replacementEnded));
    registerPool(manager, "added", trackingPool(added, addedEnded));
    manager.restoreConfigs(snapshot);

    assertTrue(replacementEnded.get());
    assertTrue(addedEnded.get());
    assertEquals(snapshot, manager.snapshotConfigs());
    assertTrue(manager.hasSource("ibmi"));
    assertFalse(manager.hasSource("added"));
    assertFalse(poolMap(manager).containsKey("ibmi"));
    assertFalse(poolMap(manager).containsKey("added"));
    manager.close(Duration.ZERO);
  }

  @Test
  void closeProceedsAfterGraceWhenQueryStillInFlight() {
    SourceManager manager = new SourceManager(Map.of());
    manager.beginQuery("ibmi");
    long start = System.nanoTime();
    manager.close(Duration.ofMillis(100));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 90);
    manager.endQuery("ibmi");
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
    manager.beginQuery("ibmi");

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
    manager.endQuery("ibmi");
    shutdownThread.join(2000);
  }

  @Test
  void closeContinuesWhenOnePoolEndFails() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE, SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
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

  @Test
  void awaitQuery_returnsCompletedResult() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, 1_000, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    assertEquals("ok", manager.awaitQuery("ibmi", CompletableFuture.completedFuture("ok"), null));
  }

  @Test
  void awaitQuery_timeoutEvictsPool() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, 50, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    AtomicBoolean ended = new AtomicBoolean(false);
    Pool pool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        ended.set(true);
      }
    };
    registerPool(manager, "ibmi", pool);
    manager.putHealth("ibmi", new SourceManager.PoolHealth(true, false, "healthy", Instant.now()));

    TimeoutException timeout = assertThrows(
        TimeoutException.class,
        () -> manager.awaitQuery("ibmi", new CompletableFuture<>(), pool));
    assertTrue(timeout.getMessage().contains("50ms"));
    assertTrue(timeout.getMessage().contains("ibmi"));
    assertTrue(ended.get(), "timed-out pool should be ended");
    assertFalse(poolMap(manager).containsKey("ibmi"));
    assertEquals("unhealthy", manager.getHealthSummary().get("ibmi").get("healthStatus"));
  }

  @Test
  void awaitQuery_timeoutDoesNotEvictReplacedPool() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, 200, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    AtomicBoolean oldEnded = new AtomicBoolean(false);
    AtomicBoolean newEnded = new AtomicBoolean(false);
    Pool oldPool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        oldEnded.set(true);
      }
    };
    Pool newPool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        newEnded.set(true);
      }
    };
    registerPool(manager, "ibmi", oldPool);
    manager.putHealth("ibmi", new SourceManager.PoolHealth(true, false, "healthy", Instant.now()));

    CompletableFuture<String> never = new CompletableFuture<>();
    Thread waiter = new Thread(() -> {
      try {
        manager.awaitQuery("ibmi", never, oldPool);
      } catch (TimeoutException expected) {
        // expected
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    waiter.start();
    Thread.sleep(50);
    manager.evictPool("ibmi");
    registerPool(manager, "ibmi", newPool);
    manager.putHealth("ibmi", new SourceManager.PoolHealth(true, false, "healthy", Instant.now()));
    waiter.join(3000);

    assertTrue(oldEnded.get(), "original timed-out pool should have been ended by concurrent evict");
    assertFalse(newEnded.get(), "replacement pool must not be ended by delayed timeout eviction");
    assertTrue(poolMap(manager).containsKey("ibmi"));
    assertSame(newPool, poolMap(manager).get("ibmi"));
  }

  @Test
  void awaitQuery_timeoutWithStaleExpectedDoesNotEvictCurrentPool() throws Exception {
    // Close-after-execute-timeout race: map already holds a rebuilt pool, but the
    // waiter still passes the old pool that owned the future.
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, 50, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    AtomicBoolean oldEnded = new AtomicBoolean(false);
    AtomicBoolean newEnded = new AtomicBoolean(false);
    Pool oldPool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        oldEnded.set(true);
      }
    };
    Pool newPool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        newEnded.set(true);
      }
    };
    registerPool(manager, "ibmi", newPool);
    manager.putHealth("ibmi", new SourceManager.PoolHealth(true, false, "healthy", Instant.now()));

    assertThrows(
        TimeoutException.class,
        () -> manager.awaitQuery("ibmi", new CompletableFuture<>(), oldPool));

    assertFalse(oldEnded.get());
    assertFalse(newEnded.get());
    assertSame(newPool, poolMap(manager).get("ibmi"));
  }

  @Test
  void evictPoolIfSame_noopWhenPoolAlreadyReplaced() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS,
        SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    AtomicBoolean oldEnded = new AtomicBoolean(false);
    AtomicBoolean newEnded = new AtomicBoolean(false);
    Pool oldPool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        oldEnded.set(true);
      }
    };
    Pool newPool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        newEnded.set(true);
      }
    };
    registerPool(manager, "ibmi", newPool);
    manager.putHealth("ibmi", new SourceManager.PoolHealth(true, false, "healthy", Instant.now()));

    manager.evictPoolIfSame("ibmi", oldPool);

    assertFalse(oldEnded.get());
    assertFalse(newEnded.get());
    assertSame(newPool, poolMap(manager).get("ibmi"));
    assertEquals("healthy", manager.getHealthSummary().get("ibmi").get("healthStatus"));
  }

  @Test
  void awaitQuery_zeroTimeoutDisablesTimeout() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS, 0, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    CompletableFuture<String> delayed = new CompletableFuture<>();
    Thread completer = new Thread(() -> {
      try {
        Thread.sleep(80);
        delayed.complete("late");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    completer.start();
    assertEquals("late", manager.awaitQuery("ibmi", delayed, null));
    completer.join();
  }

  @Test
  void closeIdlePools_evictsPoolPastThreshold() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        100, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    AtomicBoolean ended = new AtomicBoolean(false);
    Pool pool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        ended.set(true);
      }
    };
    registerPool(manager, "ibmi", pool);
    manager.putHealth(
        "ibmi",
        new SourceManager.PoolHealth(true, false, "healthy", Instant.now().minusMillis(500)));

    manager.closeIdlePools();

    assertTrue(ended.get());
    assertFalse(poolMap(manager).containsKey("ibmi"));
    assertEquals("unknown", manager.getHealthSummary().get("ibmi").get("healthStatus"));
  }

  @Test
  void closeIdlePools_skipsWhenQueryInFlight() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        100, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    AtomicBoolean ended = new AtomicBoolean(false);
    Pool pool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        ended.set(true);
      }
    };
    registerPool(manager, "ibmi", pool);
    manager.putHealth(
        "ibmi",
        new SourceManager.PoolHealth(true, false, "healthy", Instant.now().minusMillis(500)));

    manager.beginQuery("ibmi");
    try {
      manager.closeIdlePools();
      assertFalse(ended.get());
      assertTrue(poolMap(manager).containsKey("ibmi"));
    } finally {
      manager.endQuery("ibmi");
    }
  }

  @Test
  void closeIdlePools_reclaimsIdleSourceWhileOtherSourceBusy() throws Exception {
    SourceConfig busyCfg = new SourceConfig(
        "busy", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        100, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceConfig idleCfg = new SourceConfig(
        "idle", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        100, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("busy", busyCfg, "idle", idleCfg));

    AtomicBoolean busyEnded = new AtomicBoolean(false);
    AtomicBoolean idleEnded = new AtomicBoolean(false);
    Pool busyPool = new Pool(SourceManager.poolOptionsFor(busyCfg)) {
      @Override
      public void end() {
        busyEnded.set(true);
      }
    };
    Pool idlePool = new Pool(SourceManager.poolOptionsFor(idleCfg)) {
      @Override
      public void end() {
        idleEnded.set(true);
      }
    };
    registerPool(manager, "busy", busyPool);
    registerPool(manager, "idle", idlePool);
    Instant stale = Instant.now().minusMillis(500);
    manager.putHealth("busy", new SourceManager.PoolHealth(true, false, "healthy", stale));
    manager.putHealth("idle", new SourceManager.PoolHealth(true, false, "healthy", stale));

    manager.beginQuery("busy");
    try {
      manager.closeIdlePools();
      assertFalse(busyEnded.get());
      assertTrue(poolMap(manager).containsKey("busy"));
      assertTrue(idleEnded.get());
      assertFalse(poolMap(manager).containsKey("idle"));
    } finally {
      manager.endQuery("busy");
    }
  }

  @Test
  void closeIdlePools_noopWhenIdleTimeoutDisabled() throws Exception {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        0, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    AtomicBoolean ended = new AtomicBoolean(false);
    Pool pool = new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        ended.set(true);
      }
    };
    registerPool(manager, "ibmi", pool);
    manager.putHealth(
        "ibmi",
        new SourceManager.PoolHealth(true, false, "healthy", Instant.now().minusSeconds(60)));

    manager.closeIdlePools();

    assertFalse(ended.get());
    assertTrue(poolMap(manager).containsKey("ibmi"));
  }

  @Test
  void startIdleTimer_noopWhenAllIdleTimeoutsDisabled() {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        0, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    manager.startIdleTimer();
    assertFalse(manager.idleTimerRunning());
  }

  @Test
  void startIdleTimer_startsWhenPositiveIdleConfigured() {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        30_000, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));

    manager.startIdleTimer();
    assertTrue(manager.idleTimerRunning());
    manager.stopIdleTimer();
    assertFalse(manager.idleTimerRunning());
  }

  @Test
  void close_stopsIdleTimer() {
    SourceConfig source = new SourceConfig(
        "ibmi", "h", 8076, "u", "p", false,
        SourceConfig.DEFAULT_MAX_SIZE, SourceConfig.DEFAULT_STARTING_SIZE,
        30_000, SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS, Map.of());
    SourceManager manager = new SourceManager(Map.of("ibmi", source));
    manager.startIdleTimer();
    assertTrue(manager.idleTimerRunning());

    manager.close(Duration.ZERO);
    assertFalse(manager.idleTimerRunning());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Pool> poolMap(SourceManager manager) throws Exception {
    Field poolsField = SourceManager.class.getDeclaredField("pools");
    poolsField.setAccessible(true);
    return (Map<String, Pool>) poolsField.get(manager);
  }

  @SuppressWarnings("unchecked")
  private static void registerPool(SourceManager manager, String name, Pool pool) throws Exception {
    Field poolsField = SourceManager.class.getDeclaredField("pools");
    poolsField.setAccessible(true);
    Map<String, Pool> pools = (Map<String, Pool>) poolsField.get(manager);
    pools.put(name, pool);
  }

  private static SourceConfig source(String name, String host) {
    return source(name, host, "pass");
  }

  private static SourceConfig source(String name, String host, String password) {
    return new SourceConfig(
        name,
        host,
        8076,
        "user",
        password,
        false,
        SourceConfig.DEFAULT_MAX_SIZE,
        SourceConfig.DEFAULT_STARTING_SIZE,
        SourceConfig.DEFAULT_MCP_POOL_IDLE_TIMEOUT_MS,
        SourceConfig.DEFAULT_MCP_POOL_QUERY_TIMEOUT_MS,
        Map.of());
  }

  private static Pool trackingPool(SourceConfig source, AtomicBoolean ended) {
    return new Pool(SourceManager.poolOptionsFor(source)) {
      @Override
      public void end() {
        ended.set(true);
      }
    };
  }
}
