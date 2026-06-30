package com.ibm.ibmi.mcp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class EofNotifyingInputStreamTest {

  @Test
  void eofCallbackFiresOnceWhenStreamIsExhausted() throws IOException {
    AtomicInteger eofCount = new AtomicInteger();
    byte[] data = "hello\n".getBytes(StandardCharsets.UTF_8);
    try (var in = new EofNotifyingInputStream(new ByteArrayInputStream(data), eofCount::incrementAndGet)) {
      while (in.read() != -1) {
        // drain
      }
    }
    assertEquals(1, eofCount.get());
  }

  @Test
  void eofCallbackDoesNotFireOnFirstRead() throws IOException {
    AtomicInteger eofCount = new AtomicInteger();
    byte[] data = "x".getBytes(StandardCharsets.UTF_8);
    try (var in = new EofNotifyingInputStream(new ByteArrayInputStream(data), eofCount::incrementAndGet)) {
      assertEquals('x', in.read());
      assertFalse(in.read() == 'x');
    }
    assertEquals(1, eofCount.get());
  }
}
