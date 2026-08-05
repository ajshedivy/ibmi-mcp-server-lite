package com.ibm.ibmi.mcp.mapepire;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.ibmi.mcp.config.SourceConfig;

import io.github.mapepire_ibmi.Pool;
import io.github.mapepire_ibmi.types.DaemonServer;
import io.github.mapepire_ibmi.types.JDBCOptions;
import io.github.mapepire_ibmi.types.PoolOptions;

/**
 * Owns one lazily-initialized Mapepire {@link Pool} per YAML source.
 *
 * <p>Per-source {@code mcp-pool-idle-timeout-ms} closes pools that have been idle
 * longer than the threshold; the next {@link #getPool} recreates them. Set to
 * {@code 0} to disable. Query waits use {@link #awaitQuery}.
 */
public final class SourceManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SourceManager.class);

  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);
  static final long MIN_IDLE_CHECK_INTERVAL_MS = 10_000L;

  /**
   * Cached pool health for probes. Mapepire's Java {@link Pool} does not expose
   * initialized/connecting/health fields, so we track them here.
   *
   * <p>{@code lastActivityAt} is updated on pool lifecycle events (connect / fail /
   * evict) and on successful queries.
   */
  public record PoolHealth(
      boolean initialized,
      boolean connecting,
      String healthStatus,
      Instant lastActivityAt) {

    Map<String, Object> toSummaryMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("initialized", initialized);
      map.put("connecting", connecting);
      map.put("healthStatus", healthStatus);
      if (lastActivityAt != null) {
        map.put("lastActivityAt", lastActivityAt.toString());
      }
      return map;
    }
  }

  private final Map<String, SourceConfig> sources;
  private final Map<String, Pool> pools = new ConcurrentHashMap<>();
  private final Map<String, PoolHealth> health = new ConcurrentHashMap<>();
  /** Per-source in-flight tool queries — idle reclaim skips only busy pools. */
  private final ConcurrentHashMap<String, AtomicInteger> inFlightBySource = new ConcurrentHashMap<>();
  private ScheduledExecutorService idleScheduler;

  public SourceManager(Map<String, SourceConfig> sources) {
    this.sources = sources;
  }

  /** Called when a tool query starts on {@code sourceName}; paired with {@link #endQuery}. */
  public void beginQuery(String sourceName) {
    inFlightBySource.computeIfAbsent(sourceName, ignored -> new AtomicInteger()).incrementAndGet();
  }

  /** Called when a tool query finishes on {@code sourceName}; paired with {@link #beginQuery}. */
  public void endQuery(String sourceName) {
    AtomicInteger counter = inFlightBySource.get(sourceName);
    if (counter == null) {
      return;
    }
    counter.decrementAndGet();
  }

  public boolean hasSource(String sourceName) {
    return sources.containsKey(sourceName);
  }

  /** Returns an initialized pool for the named source, connecting on first use. */
  public synchronized Pool getPool(String sourceName) throws Exception {
    Pool existing = pools.get(sourceName);
    if (existing != null) {
      return existing;
    }
    SourceConfig source = sources.get(sourceName);
    if (source == null) {
      throw new IllegalArgumentException("Unknown source: " + sourceName);
    }
    markConnecting(sourceName);
    PoolOptions options = poolOptionsFor(source);
    Pool pool = new Pool(options);
    Object libs = source.jdbcOptions().get("libraries");
    if (libs instanceof List<?> list && !list.isEmpty()) {
      log.info("Connecting pool to Mapepire at {}:{} as {} (libraries: {}, max-size={}, starting-size={})",
          source.host(), source.port(), source.user(), list, source.maxSize(), source.startingSize());
    } else {
      log.info("Connecting pool to Mapepire at {}:{} as {} (max-size={}, starting-size={})",
          source.host(), source.port(), source.user(), source.maxSize(), source.startingSize());
    }
    try {
      pool.init().get();
    } catch (Exception e) {
      health.put(sourceName, new PoolHealth(false, false, "unhealthy", Instant.now()));
      try {
        pool.end();
      } catch (Exception endEx) {
        log.warn("Error ending pool after failed init for source '{}': {}",
            sourceName, endEx.getMessage());
      }
      throw e;
    }
    log.info("Connected pool for source '{}'", sourceName);
    pools.put(sourceName, pool);
    health.put(sourceName, new PoolHealth(true, false, "healthy", Instant.now()));
    startIdleTimer();
    return pool;
  }

  /**
   * Closes and removes a pool after a failure (timeout / connection error) so the next
   * {@link #getPool} rebuilds it. Marks health {@code unhealthy} (degrades {@code /healthz}).
   */
  public synchronized void evictPool(String sourceName) {
    closePool(sourceName, "unhealthy");
  }

  /**
   * Evicts {@code sourceName} only if the map still holds {@code expected}. Avoids a
   * timed-out caller removing a pool that another thread already replaced.
   */
  public synchronized void evictPoolIfSame(String sourceName, Pool expected) {
    if (expected == null || pools.get(sourceName) != expected) {
      return;
    }
    evictPool(sourceName);
  }

  /**
   * Closes an idle pool without marking it unhealthy (Node {@code closePool} parity).
   * {@code /healthz} stays {@code ok} with {@code healthStatus: unknown}.
   */
  synchronized void closeIdlePool(String sourceName) {
    closePool(sourceName, "unknown");
  }

  private synchronized void closePool(String sourceName, String healthStatus) {
    Pool pool = pools.remove(sourceName);
    if (pool == null) {
      return;
    }
    health.put(sourceName, new PoolHealth(false, false, healthStatus, Instant.now()));
    try {
      pool.end();
      log.info("Closed pool for source '{}' (status={})", sourceName, healthStatus);
    } catch (Exception e) {
      log.warn("Error ending pool for source '{}': {}", sourceName, e.getMessage());
    }
  }

  /**
   * Waits for a Mapepire execute/fetch/close future using the source's query timeout.
   * On timeout, evicts the pool that was present when the wait started (identity check)
   * so a concurrent reconnect is not torn down. When {@code mcp-pool-query-timeout-ms <= 0},
   * waits indefinitely.
   */
  public <T> T awaitQuery(String sourceName, CompletableFuture<T> future) throws Exception {
    SourceConfig source = sources.get(sourceName);
    if (source == null) {
      throw new IllegalArgumentException("Unknown source: " + sourceName);
    }
    Pool poolSnapshot = pools.get(sourceName);
    int timeoutMs = source.mcpPoolQueryTimeoutMs();
    try {
      if (timeoutMs <= 0) {
        return future.get();
      }
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.error(
          "Query timed out after {}ms on pool '{}'. Closing pool for re-initialization.",
          timeoutMs,
          sourceName);
      evictPoolIfSame(sourceName, poolSnapshot);
      throw new TimeoutException(
          "Query timed out after " + timeoutMs + "ms on pool '" + sourceName
              + "'. The connection may be stale. Pool will be re-initialized on the next request.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    }
  }

  /**
   * Records a successful query against {@code sourceName}. Updates
   * {@code lastActivityAt} and marks the pool healthy (Node SourceManager parity).
   */
  public synchronized void recordActivity(String sourceName) {
    PoolHealth current = health.get(sourceName);
    if (current == null) {
      return;
    }
    health.put(sourceName, new PoolHealth(
        current.initialized(),
        current.connecting(),
        "healthy",
        Instant.now()));
  }

  /**
   * Lightweight health summary for sources that have attempted a connection. Suitable for
   * {@code GET /healthz}. Cached lifecycle state only — does not probe Mapepire.
   */
  public Map<String, Map<String, Object>> getHealthSummary() {
    Map<String, Map<String, Object>> summary = new LinkedHashMap<>();
    for (Map.Entry<String, PoolHealth> entry : health.entrySet()) {
      summary.put(entry.getKey(), entry.getValue().toSummaryMap());
    }
    return summary;
  }

  /** Test hook to inject cached health without connecting to Mapepire. */
  public void putHealth(String sourceName, PoolHealth poolHealth) {
    health.put(sourceName, poolHealth);
  }

  /**
   * Marks a source as connecting. Keeps {@code unhealthy} sticky across reconnect so
   * {@code /healthz} stays degraded until init succeeds or fails; first-time connect
   * uses {@code unknown}.
   */
  void markConnecting(String sourceName) {
    PoolHealth previous = health.get(sourceName);
    String status = previous != null && "unhealthy".equals(previous.healthStatus())
        ? "unhealthy"
        : "unknown";
    health.put(sourceName, new PoolHealth(false, true, status, Instant.now()));
  }

  static PoolOptions poolOptionsFor(SourceConfig source) {
    // Mapepire's flag is rejectUnauthorized — the inverse of YAML's ignore-unauthorized.
    DaemonServer server = new DaemonServer(
        source.host(), source.port(), source.user(), source.password(),
        !source.ignoreUnauthorized());
    if (source.jdbcOptions().isEmpty()) {
      return new PoolOptions(server, source.maxSize(), source.startingSize());
    }
    JDBCOptions jdbcOptions = JdbcOptionsMapper.toMapepire(source.jdbcOptions());
    return new PoolOptions(server, jdbcOptions, source.maxSize(), source.startingSize());
  }

  /**
   * Starts the idle-pool checker if any source has a positive idle timeout.
   * Idempotent. Interval is {@code max(10s, minIdleTimeout / 2)}.
   */
  synchronized void startIdleTimer() {
    if (idleScheduler != null) {
      return;
    }
    int minIdleMs = minPositiveIdleTimeoutMs();
    if (minIdleMs <= 0) {
      return;
    }
    long checkIntervalMs = Math.max(MIN_IDLE_CHECK_INTERVAL_MS, minIdleMs / 2L);
    idleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "mapepire-pool-idle");
      t.setDaemon(true);
      return t;
    });
    idleScheduler.scheduleAtFixedRate(
        this::safeCloseIdlePools, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
    log.info(
        "Pool idle timer started: checking every {}ms, closing pools idle longer than configured threshold",
        checkIntervalMs);
  }

  /** Stops the idle-pool checker. Called during shutdown. */
  public synchronized void stopIdleTimer() {
    if (idleScheduler == null) {
      return;
    }
    idleScheduler.shutdownNow();
    idleScheduler = null;
    log.debug("Pool idle timer stopped");
  }

  boolean idleTimerRunning() {
    return idleScheduler != null;
  }

  /**
   * Closes pools idle longer than their source's {@code mcp-pool-idle-timeout-ms}.
   * Skips a pool while that source still has an in-flight tool query.
   */
  synchronized void closeIdlePools() {
    Instant now = Instant.now();
    for (String name : List.copyOf(pools.keySet())) {
      if (inFlightCount(name) > 0) {
        continue;
      }
      SourceConfig config = sources.get(name);
      if (config == null) {
        continue;
      }
      int idleTimeoutMs = config.mcpPoolIdleTimeoutMs();
      if (idleTimeoutMs <= 0) {
        continue;
      }
      PoolHealth poolHealth = health.get(name);
      if (poolHealth == null || poolHealth.lastActivityAt() == null) {
        continue;
      }
      long idleDurationMs = Duration.between(poolHealth.lastActivityAt(), now).toMillis();
      if (idleDurationMs > idleTimeoutMs) {
        log.info(
            "Closing idle pool '{}' (idle for {}s, threshold {}ms)",
            name,
            Math.round(idleDurationMs / 1000.0),
            idleTimeoutMs);
        closeIdlePool(name);
      }
    }
  }

  private int inFlightCount(String sourceName) {
    AtomicInteger counter = inFlightBySource.get(sourceName);
    return counter == null ? 0 : counter.get();
  }

  private int totalInFlight() {
    int total = 0;
    for (AtomicInteger counter : inFlightBySource.values()) {
      total += Math.max(0, counter.get());
    }
    return total;
  }

  private void safeCloseIdlePools() {
    try {
      closeIdlePools();
    } catch (Exception e) {
      log.warn("Idle pool check failed: {}", e.getMessage());
    }
  }

  private int minPositiveIdleTimeoutMs() {
    int min = 0;
    for (SourceConfig source : sources.values()) {
      int idle = source.mcpPoolIdleTimeoutMs();
      if (idle > 0 && (min == 0 || idle < min)) {
        min = idle;
      }
    }
    return min;
  }

  @Override
  public void close() {
    close(SHUTDOWN_GRACE);
  }

  void close(Duration grace) {
    stopIdleTimer();
    // Do not hold the instance monitor while sleeping: getPool() is synchronized and
    // in-flight queries need it after beginQuery() to make progress toward endQuery().
    awaitInFlight(grace);
    synchronized (this) {
      pools.forEach((name, pool) -> {
        try {
          pool.end();
        } catch (Exception e) {
          log.warn("Error ending pool for source '{}': {}", name, e.getMessage());
        }
      });
      pools.clear();
      health.clear();
    }
  }

  private void awaitInFlight(Duration grace) {
    long deadline = System.nanoTime() + grace.toNanos();
    while (totalInFlight() > 0) {
      if (System.nanoTime() >= deadline) {
        log.warn("Shutdown grace elapsed with {} in-flight queries", totalInFlight());
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while waiting for in-flight queries");
        return;
      }
    }
  }
}

