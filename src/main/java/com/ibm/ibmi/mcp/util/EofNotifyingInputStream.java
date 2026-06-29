package com.ibm.ibmi.mcp.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps an {@link InputStream} and invokes a callback exactly once when EOF is observed
 * ({@code read} returns {@code -1}). The delegate stream is the sole reader — this wrapper
 * does not buffer or read ahead, so it is safe to hand to
 * {@link io.modelcontextprotocol.server.transport.StdioServerTransportProvider} while still
 * detecting client disconnect (stdin closed).
 */
public final class EofNotifyingInputStream extends InputStream {

  private final InputStream delegate;
  private final Runnable onEof;
  private final AtomicBoolean eofSignalled = new AtomicBoolean(false);

  public EofNotifyingInputStream(InputStream delegate, Runnable onEof) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.onEof = Objects.requireNonNull(onEof, "onEof");
  }

  @Override
  public int read() throws IOException {
    int b = delegate.read();
    if (b == -1) {
      signalEof();
    }
    return b;
  }

  @Override
  public int read(byte[] buf, int off, int len) throws IOException {
    int n = delegate.read(buf, off, len);
    if (n == -1) {
      signalEof();
    }
    return n;
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  private void signalEof() {
    if (eofSignalled.compareAndSet(false, true)) {
      onEof.run();
    }
  }
}
