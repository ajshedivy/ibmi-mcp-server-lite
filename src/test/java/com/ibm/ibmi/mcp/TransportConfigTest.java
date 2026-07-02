package com.ibm.ibmi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.server.TransportConfig;

class TransportConfigTest {

  @Test
  void resolveTransportConfig_defaultsToStdioSettings() {
    TransportConfig config = Main.resolveTransportConfig(
        "stdio", TransportConfig.DEFAULT_HOST, "3010", TransportConfig.DEFAULT_ENDPOINT);
    assertEquals(TransportConfig.DEFAULT_HOST, config.httpHost());
    assertEquals(3010, config.httpPort());
    assertEquals(TransportConfig.DEFAULT_ENDPOINT, config.httpEndpoint());
  }

  @Test
  void resolveTransportConfig_acceptsHttp() {
    TransportConfig config = Main.resolveTransportConfig("http", "127.0.0.1", "8080", "/mcp");
    assertEquals("127.0.0.1", config.httpHost());
    assertEquals(8080, config.httpPort());
    assertEquals("/mcp", config.httpEndpoint());
  }

  @Test
  void resolveTransportConfig_acceptsHttpCaseInsensitive() {
    TransportConfig config = Main.resolveTransportConfig("HTTP", "127.0.0.1", "8080", "/mcp");
    assertEquals(8080, config.httpPort());
  }

  @Test
  void resolveTransportConfig_rejectsInvalidTransport() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveTransportConfig("websocket", "0.0.0.0", "3010", "/mcp"));
  }

  @Test
  void resolveTransportConfig_rejectsInvalidPort() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveTransportConfig("http", "0.0.0.0", "not-a-port", "/mcp"));
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveTransportConfig("http", "0.0.0.0", "0", "/mcp"));
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveTransportConfig("http", "0.0.0.0", "70000", "/mcp"));
  }

  @Test
  void resolveTransportConfig_rejectsEndpointWithoutLeadingSlash() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveTransportConfig("http", "0.0.0.0", "3010", "mcp"));
  }

  @Test
  void resolveTransportConfig_rejectsBlankHost() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveTransportConfig("http", "  ", "3010", "/mcp"));
  }
}
