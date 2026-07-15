package com.ibm.ibmi.mcp.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ibm.ibmi.mcp.config.SourceConfig;
import com.ibm.ibmi.mcp.mapepire.SourceManager;

import io.github.mapepire_ibmi.Pool;
import io.github.mapepire_ibmi.Query;
import io.github.mapepire_ibmi.types.QueryResult;

/**
 * Live connectivity smoke: SourceManager → Mapepire Pool → trivial SELECT.
 */
class SourceManagerIT {

  private static final String SOURCE_NAME = "system";

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void connectsAndRunsSelectOne() throws Exception {
    MapepireEnv.assumeAvailable();

    SourceConfig source = MapepireEnv.sourceConfig(SOURCE_NAME);
    try (SourceManager manager = new SourceManager(Map.of(SOURCE_NAME, source))) {
      Pool pool = manager.getPool(SOURCE_NAME);
      assertNotNull(pool);

      Query query = pool.query("SELECT 1 AS ONE FROM SYSIBM.SYSDUMMY1");
      try {
        QueryResult<Object> result = query.<Object>execute(1).get();
        assertNotNull(result);
        List<Object> rows = result.getData();
        assertNotNull(rows);
        assertFalse(rows.isEmpty(), "expected at least one row from SELECT 1");
        assertEquals(1, rows.size());
      } finally {
        query.close().get();
      }
    }
  }
}
