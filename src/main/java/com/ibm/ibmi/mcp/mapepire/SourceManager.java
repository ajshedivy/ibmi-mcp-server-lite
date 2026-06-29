package com.ibm.ibmi.mcp.mapepire;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.ibmi.mcp.config.SourceConfig;

import io.github.mapepire_ibmi.Pool;
import io.github.mapepire_ibmi.types.DaemonServer;
import io.github.mapepire_ibmi.types.JDBCOptions;

/**
 * Owns one lazily-initialized Mapepire {@link Pool} per YAML source.
 *
 * <p>A single job serializes queries against that source — fine for an MVP.
 * TODO: replace with {@code io.github.mapepire_ibmi.Pool} for concurrent tool
 * calls and add reconnect-on-failure.
 */
public final class SourceManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SourceManager.class);

  private final Map<String, SourceConfig> sources;
  private final Map<String, Pool> pools = new ConcurrentHashMap<>();

  public SourceManager(Map<String, SourceConfig> sources) {
    this.sources = sources;
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
    PoolOptions options = poolOptionsFor(source);
    Pool pool = new Pool(options);
    log.info("Connecting pool to Mapepire at {}:{} as {} (max-size={}, starting-size={})",
        source.host(), source.port(), source.user(), source.maxSize(), source.startingSize());
    try {
      pool.init().get();
    } catch (Exception e) {
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
    return pool;
  }

  /** Closes and removes a pool so the next {@link #getPool} call rebuilds it. */
  public synchronized void evictPool(String sourceName) {
    Pool pool = pools.remove(sourceName);
    if (pool != null) {
      try {
        pool.end();
        log.info("Evicted pool for source '{}'", sourceName);
      } catch (Exception e) {
        log.warn("Error ending pool for source '{}': {}", sourceName, e.getMessage());
      }
    }
  }

  static PoolOptions poolOptionsFor(SourceConfig source) {
    // Mapepire's flag is rejectUnauthorized — the inverse of YAML's ignore-unauthorized.
    DaemonServer server = new DaemonServer(
        source.host(), source.port(), source.user(), source.password(),
        !source.ignoreUnauthorized());
    SqlJob job;
    if (source.jdbcOptions().isEmpty()) {
      job = new SqlJob();
    } else {
      JDBCOptions jdbcOptions = JdbcOptionsMapper.toMapepire(source.jdbcOptions());
      job = new SqlJob(jdbcOptions);
    }
    Object libs = source.jdbcOptions().get("libraries");
    if (libs instanceof List<?> list && !list.isEmpty()) {
      log.info("Connecting to Mapepire at {}:{} as {} (libraries: {})",
          source.host(), source.port(), source.user(), list);
    } else {
      log.info("Connecting to Mapepire at {}:{} as {}", source.host(), source.port(), source.user());
    }
    job.connect(server).get();
    log.info("Connected to source '{}'", sourceName);
    jobs.put(sourceName, job);
    return job;
  }

  @Override
  public synchronized void close() {
    pools.forEach((name, pool) -> {
      try {
        pool.end();
      } catch (Exception e) {
        log.warn("Error ending pool for source '{}': {}", name, e.getMessage());
      }
    });
    pools.clear();
  }
}
