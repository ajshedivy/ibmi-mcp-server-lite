package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.mapepire.SourceManager;

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
    McpServerRunner.ServerHandle handle = new McpServerRunner.ServerHandle(server, new SourceManager(Map.of()));
    assertDoesNotThrow(() -> {
      handle.close();
      handle.close();
    });
  }
}
