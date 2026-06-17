# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, and others via the
`CLAUDE.md` symlink) when working with code in this repository.

## Project Overview

A minimal Java 17 MCP server for IBM i that implements a faithful **subset** of the
[IBM i MCP Server](https://github.com/IBM/ibmi-mcp-server) (Node.js) YAML tool schema.
Tools are declarative SQL defined in YAML; execution goes to Db2 for i over Mapepire
(mapepire-java). This is an intern starter project — the reference Node implementation
is the behavioral contract, and a sibling clone usually lives at `../ibmi-mcp-server`.

**When semantics are ambiguous, match the reference server.** The distilled contract
(env interpolation rules, parameter optionality, security defaults, output shape) is in
`docs/research/yaml-schema-contract.md`; deliberately unimplemented features are
catalogued in `docs/roadmap.md` — check it before adding a feature, and update it
if you implement one.

## Commands

```bash
./mvnw package                            # build fat jar + run all unit tests
./mvnw test                               # unit tests only (no IBM i needed)
./mvnw test -Dtest=ParameterProcessorTest # single test class
./mvnw test -Dtest=ParameterProcessorTest#methodName

# Full-protocol smoke test against a live IBM i (initialize → tools/list → tools/call).
# Needs .env (cp .env.example .env). Spawns the built jar, so package first.
python3 scripts/smoke-test.py

# Run the server / inspect config
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml --list-toolsets
```

There is no lint step. Tests are JUnit 5 via surefire.

## Architecture

One pipeline, one package per stage (`src/main/java/com/ibm/ibmi/mcp/`):

```
YAML file ──(config: env-interpolate raw text, SnakeYAML SafeConstructor → records)
         ──(schema: ParameterConfig list → JSON Schema string for MCP inputSchema)
         ──(server: McpServerRunner registers one MCP tool per enabled SqlToolConfig,
                    SqlToolHandler handles tools/call)
         ──(sql: ParameterProcessor binds :name → ? placeholders;
                 SqlSecurityValidator enforces read-only)
         ──(mapepire: SourceManager holds one lazy SqlJob per source)
```

Semantics live in the **config records**, not the loader: `ParameterConfig.isRequiredInSchema()`
(a parameter is required unless `required: false` or a `default` exists),
`SecurityConfig.DEFAULTS` (readOnly=true, maxQueryLength=10000), `SourceConfig`'s port
default 8076. `${VAR}` interpolation happens on the **raw file text before YAML parsing**;
unknown variables stay verbatim (not an error). Process environment wins over `.env`
entries (`util/DotEnv`).

Security model: tools are read-only by default — `SqlSecurityValidator` allows only
SELECT/WITH plus a forbidden-keyword scan (string literals and `--` comments stripped
first). Validation runs at load time for all selected tools, and again at call time only
for tools with an explicit `security:` block. `:name` values are never spliced into SQL —
they become `?` bind parameters; arrays expand to one `?` per element, booleans bind as 1/0.

Tool results mirror the reference server's `StandardSqlToolOutput` shape
(`{success, data, metadata}`), returned both as a JSON text block and as MCP
`structuredContent`.

## Critical Constraints

- **stdout belongs to the MCP stdio transport.** All logging must go to stderr
  (slf4j-simple is configured for this via `simplelogger.properties`; `Main` sets the
  log level system property before the first logger is created). Never add a
  `System.out.println`.
- **Jackson 2 only.** Use the `mcp-core` + `mcp-json-jackson2` artifacts. The bare `mcp`
  aggregator artifact pulls `mcp-json-jackson3` (Jackson 3, `tools.jackson` namespace)
  and conflicts with mapepire-sdk's Jackson 2.
- **Java 17 is the floor** (MCP SDK class-file requirement) — and IBM i's yum repo only
  ships OpenJDK 11. On-box runtime options and PASE pitfalls (`QIBM_*` stdio env vars,
  failed AIX-JDK experiments) are documented in `docs/running-on-ibmi.md`.
- **mapepire-java verifies TLS hostnames even with `ignore-unauthorized: true`** (only
  cert-chain trust is skipped). `DB2i_HOST` must match a SAN in the Mapepire server
  certificate or connections fail.
- **`.github/workflows/rpm-ibmi.yml` is a deliberately inert skeleton**, guarded by the
  `ENABLE_IBMI_RPM_BUILD` repo variable. Do not "fix" it to run; the spec and Makefile
  under `packaging/` are templates with TODO markers.

## Conventions

- All commits use DCO sign-off: `git commit -s`. Keep commits atomic
  (one logical change each, `type: description` subject style — see `git log`).
- `dependency-reduced-pom.xml` is shade-plugin output; it is gitignored, never edit it.
