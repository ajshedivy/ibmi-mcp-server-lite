package com.ibm.ibmi.mcp.mapepire;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.ibmi.mcp.config.SourceConfig;

import io.github.mapepire_ibmi.SqlJob;
import io.github.mapepire_ibmi.types.DaemonServer;

/**
 * Owns one lazily-connected Mapepire {@link SqlJob} per YAML source.
 *
 * <p>A single job serializes queries against that source — fine for an MVP.
 * INTERN TODO: replace with {@code io.github.mapepire_ibmi.Pool} for concurrent tool
 * calls, add reconnect-on-failure, and honor {@code jdbc-options} from the YAML source.
 */
public final class SourceManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SourceManager.class);

  private final Map<String, SourceConfig> sources;
  private final Map<String, SqlJob> jobs = new ConcurrentHashMap<>();

  public SourceManager(Map<String, SourceConfig> sources) {
    this.sources = sources;
  }

  /** Returns a connected job for the named source, connecting on first use. */
  public synchronized SqlJob getJob(String sourceName) throws Exception {
    SqlJob existing = jobs.get(sourceName);
    if (existing != null) {
      return existing;
    }
    SourceConfig source = sources.get(sourceName);
    if (source == null) {
      throw new IllegalArgumentException("Unknown source: " + sourceName);
    }
    // Mapepire's flag is rejectUnauthorized — the inverse of YAML's ignore-unauthorized.
    DaemonServer server = new DaemonServer(
        source.host(), source.port(), source.user(), source.password(),
        !source.ignoreUnauthorized());
    SqlJob job = new SqlJob();
    log.info("Connecting to Mapepire at {}:{} as {}", source.host(), source.port(), source.user());
    job.connect(server).get();
    log.info("Connected to source '{}'", sourceName);
    jobs.put(sourceName, job);
    return job;
  }

  @Override
  public void close() {
    jobs.forEach((name, job) -> {
      try {
        job.close();
      } catch (Exception e) {
        log.warn("Error closing job for source '{}': {}", name, e.getMessage());
      }
    });
    jobs.clear();
  }
}
