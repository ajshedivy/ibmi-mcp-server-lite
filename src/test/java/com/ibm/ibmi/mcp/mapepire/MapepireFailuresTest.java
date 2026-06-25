package com.ibm.ibmi.mcp.mapepire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

class MapepireFailuresTest {

  @Test
  void detectsIOException() {
    assertTrue(MapepireFailures.isConnectionLevel(new IOException("connection reset")));
  }

  @Test
  void detectsSqlState08ConnectionFailure() {
    assertTrue(MapepireFailures.isConnectionLevel(new SQLException("failed", "08001", 0)));
  }

  @Test
  void detectsConnectionMessageInCauseChain() {
    var wrapped = new ExecutionException(new RuntimeException("WebSocket closed unexpectedly"));
    assertTrue(MapepireFailures.isConnectionLevel(wrapped));
  }

  @Test
  void ignoresOrdinarySqlErrors() {
    assertFalse(MapepireFailures.isConnectionLevel(new SQLException("syntax error", "42601", -104)));
    assertFalse(MapepireFailures.isConnectionLevel(new IllegalArgumentException("bad parameter")));
  }
}
