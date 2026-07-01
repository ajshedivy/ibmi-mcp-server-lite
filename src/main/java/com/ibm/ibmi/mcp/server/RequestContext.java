package com.ibm.ibmi.mcp.server;

import java.security.SecureRandom;

/**
 * Per-call metadata for correlating log lines on stderr.
 *
 * @param requestId   short alphanumeric id (8 chars, A-Z0-9)
 * @param toolName    MCP tool name for this call
 * @param startMillis wall-clock start time in milliseconds
 */
public record RequestContext(String requestId, String toolName, long startMillis) {

  private static final String ID_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int ID_LENGTH = 8;

  /**
   * Creates a fresh context for a tool call.
   *
   * @param toolName the tool being executed
   */
  public static RequestContext create(String toolName) {
    return new RequestContext(generateRequestId(), toolName, System.currentTimeMillis());
  }

  private static String generateRequestId() {
    char[] chars = new char[ID_LENGTH];
    for (int i = 0; i < ID_LENGTH; i++) {
      chars[i] = ID_CHARSET.charAt(Holder.RANDOM.nextInt(ID_CHARSET.length()));
    }
    return new String(chars);
  }

  private static final class Holder {
    static final SecureRandom RANDOM = new SecureRandom();
  }
}
