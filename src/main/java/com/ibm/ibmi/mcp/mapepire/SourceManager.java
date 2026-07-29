package com.ibm.ibmi.mcp.mapepire;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 */
public final class SourceManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SourceManager.class);

  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

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
  private final AtomicInteger inFlightQueries = new AtomicInteger(0);

  public SourceManager(Map<String, SourceConfig> sources) {
    this.sources = sources;
  }

  /** Called when a tool query starts; paired with {@link #endQuery()}. */
  public void beginQuery() {
    inFlightQueries.incrementAndGet();
  }

  /** Called when a tool query finishes; paired with {@link #beginQuery()}. */
  public void endQuery() {
    inFlightQueries.decrementAndGet();
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
    return pool;
  }

  /** Closes and removes a pool so the next {@link #getPool} call rebuilds it. */
  public synchronized void evictPool(String sourceName) {
    Pool pool = pools.remove(sourceName);
    if (pool != null) {
      health.put(sourceName, new PoolHealth(false, false, "unhealthy", Instant.now()));
      try {
        pool.end();
        log.info("Evicted pool for source '{}'", sourceName);
      } catch (Exception e) {
        log.warn("Error ending pool for source '{}': {}", sourceName, e.getMessage());
      }
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

  @Override
  public void close() {
    close(SHUTDOWN_GRACE);
  }

  void close(Duration grace) {
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
    while (inFlightQueries.get() > 0) {
      if (System.nanoTime() >= deadline) {
        log.warn("Shutdown grace elapsed with {} in-flight queries", inFlightQueries.get());
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
