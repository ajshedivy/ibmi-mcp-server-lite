package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.mapepire.SourceManager;
import com.ibm.ibmi.mcp.util.ShutdownGuard;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

class McpServerRunnerTest {

  @Test
  void serverHandleCloseIsIdempotent() {
    var jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(
            jsonMapper, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()))
        .serverInfo("test", "0")
        .capabilities(ServerCapabilities.builder().build())
        .build();
    McpServerRunner.ServerHandle handle = new McpServerRunner.ServerHandle(new SourceManager(Map.of()));
    handle.attachServer(server);
    assertDoesNotThrow(() -> {
      handle.close();
      handle.close();
    });
  }

  @Test
  void shutdownGuardRunsCleanupOnceWhenTwoPathsFire() throws InterruptedException {
    AtomicBoolean shuttingDown = new AtomicBoolean(false);
    CountDownLatch shutdownLatch = new CountDownLatch(1);
    AtomicInteger cleanupRuns = new AtomicInteger(0);
    Runnable shutdown = ShutdownGuard.once(
        shuttingDown, shutdownLatch, cleanupRuns::incrementAndGet);

    Thread stdinEof = new Thread(shutdown, "stdin-eof");
    Thread shutdownHook = new Thread(shutdown, "shutdown-cleanup");
    stdinEof.start();
    shutdownHook.start();
    stdinEof.join();
    shutdownHook.join();

    assertTrue(shutdownLatch.await(2, TimeUnit.SECONDS));
    assertEquals(1, cleanupRuns.get());
  }
}
