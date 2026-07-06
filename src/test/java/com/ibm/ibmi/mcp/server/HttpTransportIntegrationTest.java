package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.ToolsConfig;
import com.ibm.ibmi.mcp.config.YamlConfigLoader;

class HttpTransportIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String MINIMAL_TOOLS_YAML = """
      sources:
        ibmi:
          host: localhost
          user: dummy
          password: dummy
      tools:
        ping:
          source: ibmi
          description: "noop"
          parameters: []
          statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
      """;

  private McpServerRunner.ServerHandle handle;

  @AfterEach
  void tearDown() {
    if (handle != null) {
      handle.close();
      handle = null;
    }
  }

  @Test
  @Timeout(15)
  void httpInitializeAndToolsList(@TempDir Path tempDir) throws Exception {
    String baseUrl = startServer(tempDir);
    HttpClient client = HttpClient.newHttpClient();

    String sessionId = initializeSession(client, baseUrl);

    postNotification(client, baseUrl, sessionId, "notifications/initialized");

    HttpResponse<String> listResponse = postJson(client, baseUrl, sessionId, """
        {
          "jsonrpc": "2.0",
          "id": 2,
          "method": "tools/list"
        }
        """);
    assertEquals(200, listResponse.statusCode(), listResponse.body());

    JsonNode tools = extractJsonRpcResult(listResponse.body()).path("tools");
    assertTrue(tools.isArray());
    assertEquals(1, tools.size());
    assertEquals("ping", tools.get(0).path("name").asText());
  }

  @Test
  @Timeout(15)
  void httpToolsCallReturnsStructuredOutput(@TempDir Path tempDir) throws Exception {
    String baseUrl = startServer(tempDir);
    HttpClient client = HttpClient.newHttpClient();

    String sessionId = initializeSession(client, baseUrl);
    postNotification(client, baseUrl, sessionId, "notifications/initialized");

    HttpResponse<String> callResponse = postJson(client, baseUrl, sessionId, """
        {
          "jsonrpc": "2.0",
          "id": 3,
          "method": "tools/call",
          "params": {
            "name": "ping",
            "arguments": {}
          }
        }
        """);
    assertEquals(200, callResponse.statusCode(), callResponse.body());

    JsonNode result = extractJsonRpcResult(callResponse.body());
    JsonNode structured = result.path("structuredContent");
    assertFalse(structured.isMissingNode() || structured.isNull(),
        "tools/call should return structuredContent over HTTP: " + result);
    assertTrue(structured.has("success"), structured::toString);
    assertFalse(structured.path("success").asBoolean(),
        "dummy creds should fail SQL execution: " + structured);
    assertTrue(structured.has("data"), structured::toString);
    assertTrue(structured.get("data").isArray(), structured::toString);
    assertTrue(structured.has("error"), structured::toString);
    assertFalse(structured.path("error").asText().isBlank(), structured::toString);
  }

  @Test
  @Timeout(15)
  void closeStopsJettyAndUnblocksJoin(@TempDir Path tempDir) throws Exception {
    startServer(tempDir);
    Server jetty = handle.jettyServer();

    CountDownLatch joined = new CountDownLatch(1);
    Thread joinThread = new Thread(() -> {
      try {
        jetty.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        joined.countDown();
      }
    }, "jetty-join");
    joinThread.start();

    McpServerRunner.ServerHandle toClose = handle;
    handle = null;
    toClose.close();

    assertTrue(joined.await(5, TimeUnit.SECONDS), "jetty.join() did not return after close()");
    assertFalse(jetty.isRunning());
  }

  private String startServer(Path tempDir) throws Exception {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, MINIMAL_TOOLS_YAML);

    ToolsConfig config = new YamlConfigLoader(Map.of()).load(yaml);
    TransportConfig transport = new TransportConfig("127.0.0.1", 0, TransportConfig.DEFAULT_ENDPOINT);
    McpServerRunner.ServerHandle[] slot = new McpServerRunner.ServerHandle[1];
    handle = McpServerRunner.startHttp(config, Set.of(), transport, slot);

    int port = HttpTransport.localPort(handle.jettyServer());
    return "http://127.0.0.1:" + port + TransportConfig.DEFAULT_ENDPOINT;
  }

  private static String initializeSession(HttpClient client, String baseUrl) throws Exception {
    HttpResponse<String> initResponse = postJson(client, baseUrl, null, """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "junit", "version": "0"}
          }
        }
        """);
    assertEquals(200, initResponse.statusCode(), initResponse.body());

    String sessionId = initResponse.headers().firstValue("mcp-session-id").orElse(null);
    assertNotNull(sessionId, "initialize should return mcp-session-id header");

    JsonNode initResult = extractJsonRpcResult(initResponse.body());
    assertEquals("ibmi-mcp-server-lite", initResult.path("serverInfo").path("name").asText());
    return sessionId;
  }

  private static void postNotification(
      HttpClient client, String baseUrl, String sessionId, String method) throws Exception {
    HttpResponse<String> response = postJson(client, baseUrl, sessionId, """
        {
          "jsonrpc": "2.0",
          "method": "%s"
        }
        """.formatted(method));
    int status = response.statusCode();
    assertTrue(status == 200 || status == 202, "notification response: " + status + " " + response.body());
  }

  private static HttpResponse<String> postJson(
      HttpClient client, String url, String sessionId, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (sessionId != null) {
      builder.header("mcp-session-id", sessionId);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode extractJsonRpcResult(String body) throws Exception {
    JsonNode root = extractJsonRpcResponse(body);
    assertFalse(root.has("error"), root.path("error").toString());
    return root.path("result");
  }

  private static JsonNode extractJsonRpcResponse(String body) throws Exception {
    String json = body.trim();
    if (!json.startsWith("{")) {
      json = json.lines()
          .filter(line -> line.startsWith("data:"))
          .map(line -> line.substring("data:".length()).trim())
          .reduce((first, second) -> second)
          .orElseThrow(() -> new IllegalStateException("No JSON in response: " + body));
    }
    return MAPPER.readTree(json);
  }
}
