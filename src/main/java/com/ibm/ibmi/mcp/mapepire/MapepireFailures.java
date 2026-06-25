package com.ibm.ibmi.mcp.mapepire;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Classifies Mapepire/transport failures that should evict a source pool for re-init.
 */
public final class MapepireFailures {

  private MapepireFailures() {}

  public static boolean isConnectionLevel(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof IOException) {
        return true;
      }
      if (current instanceof SQLException sql && isConnectionSqlState(sql)) {
        return true;
      }
      String message = current.getMessage();
      if (message != null && isConnectionMessage(message)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean isConnectionSqlState(SQLException sql) {
    String state = sql.getSQLState();
    if (state != null && state.startsWith("08")) {
      return true;
    }
    String message = sql.getMessage();
    return message != null && isConnectionMessage(message);
  }

  private static boolean isConnectionMessage(String message) {
    String lower = message.toLowerCase();
    return lower.contains("connection")
        || lower.contains("websocket")
        || lower.contains("socket")
        || lower.contains("not connected")
        || lower.contains("closed channel");
  }
}
