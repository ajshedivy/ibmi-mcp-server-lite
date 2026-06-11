# Intern roadmap

The MVP is intentionally small. Each item below is scoped, ordered roughly by
difficulty, and points at the reference implementation
([IBM/ibmi-mcp-server](https://github.com/IBM/ibmi-mcp-server), `packages/server/src`)
for the behavior to match. `docs/research/` contains detailed notes on the reference
YAML semantics, the MCP Java SDK, mapepire-java, and the IBM i RPM pipeline — read those
first.

## Starter (good first issues)

1. **Markdown response format** — honor `responseFormat: markdown`, `tableFormat`,
   `maxDisplayRows`. Reference: `mcp-server/tools/utils/toolDefinitions.ts` (the
   markdown formatter renders a header, SQL block, results table, and summary).
2. **`--list-tools` flag** — print registered tools with their toolsets and parameter
   summaries (complement to `--list-toolsets`).
3. **Enum schema parity** — emit `const` for single-value enums and `anyOf` of `const`
   for mixed-type enums like the reference (`toolConfigBuilder.ts:196-223`); append the
   `" Must be one of: ..."` description suffix.
4. **`outputSchema` registration** — register the `StandardSqlToolOutput` JSON Schema as
   each tool's MCP `outputSchema` (`Tool.builder().outputSchema(...)`).

## Core features

5. **Connection pooling** — replace `SourceManager`'s single `SqlJob` with
   `io.github.mapepire_ibmi.Pool` (`PoolOptions(creds, maxSize, startingSize)`), plus
   reconnect-on-failure. Today concurrent tool calls serialize per source.
6. **Multi-file YAML** — accept a directory or glob for `--tools`, merge files with the
   reference duplicate rules (`YAML_MERGE_ARRAYS`, `YAML_ALLOW_DUPLICATE_TOOLS`,
   `YAML_ALLOW_DUPLICATE_SOURCES`, post-merge validation).
7. **Hot reload** — watch the YAML file(s) (`WatchService`) and re-register tools via
   `server.addTool`/`removeTool` + `notifyToolsListChanged()` (`YAML_AUTO_RELOAD`).
8. **Pagination / fetchAllRows** — loop `query.fetchMore(n)` until `is_done`, with the
   reference's ~30k row safety cap.
9. **`jdbc-options` passthrough** — map the YAML `jdbc-options` block onto
   `SqlJob(JDBCOptions)`, including the `libraries` array/CSV dual form and the
   `DB2i_JDBC_OPTIONS` env override.

## Bigger projects

10. **Streamable HTTP transport** — `--transport http` using
    `HttpServletStreamableServerTransportProvider` (in `mcp-core`) inside an embedded
    Jetty/Tomcat; then the Service Commander unit in `packaging/ibmi/` becomes real.
11. **Real SQL security validation** — replace the regex strategy with a proper Db2 for
    i tokenizer/parser (the reference uses the vscode-db2i parser; only `SELECT`/`WITH`
    statement types count as read-only). Includes the `execute_sql`-style
    direct-substitution mode and per-statement-type classification.
12. **`execute_sql` built-in tool** — gated by `IBMI_ENABLE_EXECUTE_SQL`, with the
    direct-substitution special case (statement that is exactly `:sql`).
13. **Upstream mapepire-java contribution** — make `rejectUnauthorized=false` also skip
    hostname verification (override `onSetSSLParameters` on the WebSocket client), or
    add an explicit option. See docs/running-on-ibmi.md → "TLS hostname verification".
14. **Finish the RPM** — resolve the Java 17 runtime question (Semeru Certified 17 /
    future openjdk-17 RPM), complete `packaging/rpm/ibmi-mcp-server-lite.spec`, stand up
    a build partition, and enable `.github/workflows/rpm-ibmi.yml`
    (`ENABLE_IBMI_RPM_BUILD=true` + secrets).

## Quality / hardening (ongoing)

- Integration tests against a containerized or shared Mapepire (the smoke test is
  Python; consider a JUnit integration profile gated by env vars).
- Structured request logging with per-call context (the reference's `RequestContext`).
- Graceful shutdown: close in-flight queries; exit when stdin closes.
- Input validation parity: enforce `pattern`/`enum`/`min`/`max` server-side at call time
  (today they're only declared in the inputSchema for the client to enforce).
