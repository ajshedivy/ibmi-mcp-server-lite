package com.ibm.ibmi.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.server.TransportConfig;

class TransportConfigTest {

  @Test
  void validateTransportName_acceptsStdio() {
    assertDoesNotThrow(() -> Main.validateTransportName("stdio"));
    assertDoesNotThrow(() -> Main.validateTransportName("STDIO"));
  }

  @Test
  void validateTransportName_acceptsHttp() {
    assertDoesNotThrow(() -> Main.validateTransportName("http"));
    assertDoesNotThrow(() -> Main.validateTransportName("HTTP"));
  }

  @Test
  void validateTransportName_rejectsInvalidTransport() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.validateTransportName("websocket"));
  }

  @Test
  void resolveHttpTransportConfig_acceptsDefaults() {
    TransportConfig config = Main.resolveHttpTransportConfig(
        TransportConfig.DEFAULT_HOST, "3010", TransportConfig.DEFAULT_ENDPOINT);
    assertEquals(TransportConfig.DEFAULT_HOST, config.httpHost());
    assertEquals(3010, config.httpPort());
    assertEquals(TransportConfig.DEFAULT_ENDPOINT, config.httpEndpoint());
  }

  @Test
  void resolveHttpTransportConfig_acceptsCustomSettings() {
    TransportConfig config = Main.resolveHttpTransportConfig("127.0.0.1", "8080", "/mcp");
    assertEquals("127.0.0.1", config.httpHost());
    assertEquals(8080, config.httpPort());
    assertEquals("/mcp", config.httpEndpoint());
  }

  @Test
  void resolveHttpTransportConfig_rejectsInvalidPort() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveHttpTransportConfig("0.0.0.0", "not-a-port", "/mcp"));
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveHttpTransportConfig("0.0.0.0", "0", "/mcp"));
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveHttpTransportConfig("0.0.0.0", "70000", "/mcp"));
  }

  @Test
  void resolveHttpTransportConfig_rejectsEndpointWithoutLeadingSlash() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveHttpTransportConfig("0.0.0.0", "3010", "mcp"));
  }

  @Test
  void resolveHttpTransportConfig_rejectsBlankHost() {
    assertThrows(IllegalArgumentException.class,
        () -> Main.resolveHttpTransportConfig("  ", "3010", "/mcp"));
  }
}
