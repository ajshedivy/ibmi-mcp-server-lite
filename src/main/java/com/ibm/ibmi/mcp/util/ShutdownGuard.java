package com.ibm.ibmi.mcp.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ensures shutdown cleanup ({@code cleanup}) runs at most once and always releases
 * {@code shutdownLatch}, even when multiple paths invoke the returned task (e.g. stdin EOF
 * and a JVM shutdown hook).
 */
public final class ShutdownGuard {

  private ShutdownGuard() {}

  public static Runnable once(AtomicBoolean shuttingDown, CountDownLatch shutdownLatch,
      Runnable cleanup) {
    return () -> {
      if (!shuttingDown.compareAndSet(false, true)) {
        return;
      }
      try {
        cleanup.run();
      } finally {
        shutdownLatch.countDown();
      }
    };
  }
}
