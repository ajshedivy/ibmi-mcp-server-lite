<!-- Research notes generated during initial development (June 2026). Facts were verified against the cited sources at that time; re-verify versions before relying on them. -->

# MCP Java SDK — API Report (verified against v1.1.3 source + compile-checked)

**Targets: `io.modelcontextprotocol.sdk` version 1.1.3** (latest stable). All facts below verified against the `v1.1.3` git tag source and `repo1.maven.org` metadata; the example at the end **compiles cleanly against the real 1.1.3 artifacts** (Maven, exit 0).

## 1. Maven coordinates & latest version

Note: `search.maven.org/solrsearch` returns `numFound: 0` for this group (indexing gap) — verified instead via `https://repo1.maven.org/maven2/io/modelcontextprotocol/sdk/mcp/maven-metadata.xml`.

- **Latest stable: `1.1.3`**. (`2.0.0-RC1` / `2.0.0-M1..M3` exist but are pre-releases; metadata `lastUpdated` 2026-06-09. Version history: 0.7.0 … 0.18.3, 1.0.0 … 1.1.3, 2.0.0-M1..RC1.)
- **BOM exists: `io.modelcontextprotocol.sdk:mcp-bom:1.1.3`** (import scope, type pom).
- Artifacts under `io.modelcontextprotocol.sdk`:
  - `mcp` — convenience aggregator. **Caution:** in 1.x it = `mcp-core` + `mcp-json-jackson3` (Jackson **3**, `tools.jackson`). In ≤0.18.x it bundled Jackson 2 (per `MIGRATION-1.0.md`).
  - `mcp-core` — all core APIs + transports (STDIO, JDK HttpClient, Servlet) live here in 1.x.
  - `mcp-json-jackson2` / `mcp-json-jackson3` — JSON binding implementations.
  - `mcp-test`
  - `mcp-spring-webmvc`, `mcp-spring-webflux` — **frozen at 0.18.3**; Spring transports moved to Spring AI 2.0+ for the 1.x line.
  - `mcp-json`, `server-servlet`, `client-jdk-http-client`, `conformance-tests` — older/transitional or 0.18.x-only modules, not part of the 1.1.x set.

```xml
<dependencyManagement><dependencies>
  <dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-bom</artifactId>
    <version>1.1.3</version><type>pom</type><scope>import</scope>
  </dependency>
</dependencies></dependencyManagement>
<dependencies>
  <!-- For Jackson 2 (com.fasterxml.jackson): -->
  <dependency><groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-core</artifactId></dependency>
  <dependency><groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-json-jackson2</artifactId></dependency>
  <!-- OR just io.modelcontextprotocol.sdk:mcp if Jackson 3 is acceptable -->
</dependencies>
```

## 2. Minimum Java version
**Java 17** (`<java.version>17</java.version>`, compiler `<release>17</release>` in root pom; README badge "Java 17+").

## 3. Sync server with STDIO (plain Java)

- `io.modelcontextprotocol.server.McpServer` (interface) — `static SingleSessionSyncSpecification sync(McpServerTransportProvider)` → builder with `.serverInfo(String name, String version)`, `.capabilities(McpSchema.ServerCapabilities)`, `.instructions(String)`, `.requestTimeout(Duration)`, `.jsonMapper(McpJsonMapper)`, `.immediateExecution(boolean)`, `.tools(...)`, `.toolCall(Tool, handler)`, `.build()` → `McpSyncServer`.
- `io.modelcontextprotocol.server.transport.StdioServerTransportProvider` — constructors: `StdioServerTransportProvider(McpJsonMapper)` (uses System.in/out) and `(McpJsonMapper, InputStream, OutputStream)`. **No ObjectMapper overload in 1.x** — JSON is abstracted behind `io.modelcontextprotocol.json.McpJsonMapper`.
- Get a mapper: `new io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper())` (note: package renamed from `...json.jackson` → `...json.jackson2` in 1.0), or `io.modelcontextprotocol.json.McpJsonDefaults.getMapper()` (ServiceLoader discovery).
- `McpSchema.ServerCapabilities.builder()` methods: `.tools(Boolean listChanged)`, `.resources(Boolean subscribe, Boolean listChanged)`, `.prompts(Boolean listChanged)`, `.logging()`, `.completions()`.

## 4. Runtime-schema tool registration

`io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification` is a record:
```java
public record SyncToolSpecification(McpSchema.Tool tool,
    BiFunction<McpSyncServerExchange, CallToolRequest, McpSchema.CallToolResult> callHandler)
```
- **Current form: `SyncToolSpecification.builder().tool(Tool).callHandler(BiFunction).build()`** — yes, handler is exactly `BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>`.
- The old deprecated `call` component (`BiFunction<exchange, Map<String,Object>, CallToolResult>`, 0.10/0.11 era) was **removed in 1.0** — the record canonical constructor now takes only `(tool, callHandler)`.
- `McpSchema.Tool` (record: name, title, description, inputSchema, outputSchema, annotations, _meta) via `Tool.builder()`:
  - `.inputSchema(McpJsonMapper jsonMapper, String jsonSchemaString)` — **raw JSON-schema string at runtime, confirmed**; or `.inputSchema(McpSchema.JsonSchema)` where `new McpSchema.JsonSchema(String type, Map<String,Object> properties, List<String> required, Boolean additionalProperties, Map<String,Object> defs, Map<String,Object> definitions)`.
  - `.outputSchema(McpJsonMapper, String)` / `.outputSchema(Map<String,Object>)` also exist.
- Reading args: `McpSchema.CallToolRequest` is a record `(String name, Map<String,Object> arguments, Map<String,Object> _meta)` → `request.arguments().get("city")`; convert typed via `jsonMapper.convertValue(...)`.

## 5. Results

`McpSchema.CallToolResult` record `(List<Content> content, Boolean isError, Object structuredContent, Map<String,Object> _meta)`. `CallToolResult.builder()`: `.addTextContent(String)`, `.textContent(List<String>)`, `.addContent(Content)`, `.content(List<Content>)`, `.isError(Boolean)`, **`.structuredContent(Object)` and `.structuredContent(McpJsonMapper, String json)` — yes, structuredContent is supported**, `.meta(Map)`.

## 6. ToolAnnotations

`McpSchema.ToolAnnotations` record — **6 components in 1.1.3, no builder**:
```java
new McpSchema.ToolAnnotations(String title, Boolean readOnlyHint, Boolean destructiveHint,
    Boolean idempotentHint, Boolean openWorldHint, Boolean returnDirect)
```
Attach via `Tool.builder().annotations(toolAnnotations)`.

## 7. Dynamic add after start
`io.modelcontextprotocol.server.McpSyncServer`: `public void addTool(McpServerFeatures.SyncToolSpecification toolHandler)` (also `removeTool(String)`, `notifyToolsListChanged()`). Triggers `notifications/tools/list_changed` when capability `tools(true)` is set.

## 8. Streamable HTTP for plain servlet containers
`io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider` — **inside `io.modelcontextprotocol.sdk:mcp-core` (no separate artifact in 1.x)**. It's a Jakarta `HttpServlet` (`@WebServlet(asyncSupported = true)`), implements `McpStreamableServerTransportProvider`; build via `HttpServletStreamableServerTransportProvider.builder().jsonMapper(...).mcpEndpoint("/mcp").keepAliveInterval(...).build()` and pass to `McpServer.sync(...)`. (`mcp-spring-webmvc`/`mcp-spring-webflux` exist only ≤0.18.3; Spring users on 1.x get transports from Spring AI.)

## Minimal complete server (compile-verified against 1.1.3)

```java
package demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

public class StdioMcpServer {
    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(jsonMapper))
            .serverInfo("demo-server", "1.0.0")
            .capabilities(ServerCapabilities.builder().tools(true).logging().build())
            .build();

        String schema = """
            { "type": "object",
              "properties": { "city": { "type": "string", "description": "City name" } },
              "required": ["city"], "additionalProperties": false }""";

        Tool tool = Tool.builder()
            .name("get_weather")
            .description("Get weather for a city")
            .inputSchema(jsonMapper, schema)                       // runtime JSON-schema string
            .annotations(new ToolAnnotations("Weather lookup", true, false, true, true, null))
            .build();

        McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {                  // (McpSyncServerExchange, CallToolRequest)
                String city = (String) request.arguments().get("city");
                return CallToolResult.builder()
                    .addTextContent("Sunny in " + city)
                    .isError(false)
                    .build();
            })
            .build();

        server.addTool(spec);   // dynamic registration after start
        // STDIO server now serves until the client disconnects; call server.close() to stop.
    }
}
```

Verification artifacts: clone at `/tmp/mcp-java-sdk` (tag v1.1.3); compile-checked project at `/tmp/mcp-verify` (`pom.xml` + `src/main/java/demo/StdioMcpServer.java`, built with `./mvnw compile`, exit 0).

Sources: [github.com/modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk) (v1.1.3 tag source: `mcp-core/.../McpServer.java`, `McpServerFeatures.java`, `McpSchema.java`, `McpSyncServer.java`, `StdioServerTransportProvider.java`, `HttpServletStreamableServerTransportProvider.java`, `MIGRATION-1.0.md`, `README.md`), [repo1.maven.org mcp maven-metadata.xml](https://repo1.maven.org/maven2/io/modelcontextprotocol/sdk/mcp/maven-metadata.xml)