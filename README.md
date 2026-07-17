# ibmi-mcp-server-lite (under active developement!)

A **minimal MCP (Model Context Protocol) server for IBM i, written in Java**. Tools are
defined declaratively in YAML — using the same tool schema as the official
[IBM i MCP Server](https://github.com/IBM/ibmi-mcp-server) (Node.js) — and execute SQL
against Db2 for i through [Mapepire](https://mapepire-ibmi.github.io/).


```
┌─────────────┐   stdio (JSON-RPC)   ┌──────────────────────┐   wss://host:8076   ┌────────────┐
│ MCP client  │ ◄──────────────────► │ ibmi-mcp-server-lite │ ◄─────────────────► │  Mapepire  │
│ (agent/IDE) │                      │  YAML tools → SQL    │   mapepire-java     │  on IBM i  │
└─────────────┘                      └──────────────────────┘                     └────────────┘
```

Built on:

- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) 1.1.3 (`mcp-core` + `mcp-json-jackson2`)
- [mapepire-java](https://github.com/Mapepire-IBMi/mapepire-java) 0.1.3
- Java 17, Maven (wrapper included)

## Quickstart

Prerequisites: **Java 17+** on the machine running the server, and an IBM i with the
[Mapepire daemon](https://mapepire-ibmi.github.io/guides/sysadmin/) running (default port 8076).

```bash
# 1. Build (runs the unit tests too)
./mvnw package

# 2. Configure credentials used by ${VAR} interpolation in the tools YAML
cp .env.example .env   # then edit DB2i_HOST / DB2i_USER / DB2i_PASS

# 3. Sanity check: list the toolsets defined in the sample YAML
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml --list-toolsets
#    --tools also accepts a directory of YAML files or a glob (see docs/yaml-tools-reference.md)

# 4. End-to-end smoke test over real stdio JSON-RPC (initialize → tools/list → tools/call)
python3 scripts/smoke-test.py
```

> **TLS note:** with mapepire-sdk 0.1.3+, `ignore-unauthorized: true` relaxes both
> certificate-chain trust and TLS hostname (SAN) verification. Leave it `false` (the
> default) when `DB2i_HOST` matches a name in the Mapepire server certificate. See
> [docs/running-on-ibmi.md](docs/running-on-ibmi.md#tls-hostname-verification).

### HTTP transport (daemon mode)

For long-running deployment (e.g. Service Commander on IBM i), use Streamable HTTP instead
of per-client stdio:

```bash
java -jar target/ibmi-mcp-server-lite-0.1.0.jar \
  --tools tools/sample-tools.yaml \
  --transport http
```

Defaults: bind `0.0.0.0:3010`, MCP endpoint `/mcp`. Override via flags or environment:

| Flag | Environment variable | Default |
|------|---------------------|---------|
| `--transport http` | `MCP_TRANSPORT_TYPE` | `stdio` |
| `--http-port` | `MCP_HTTP_PORT` | `3010` |
| `--http-host` | `MCP_HTTP_HOST` | `0.0.0.0` |
| `--http-endpoint` | `MCP_HTTP_ENDPOINT_PATH` | `/mcp` |

CLI flags win over environment variables. The HTTP transport is **unauthenticated** for
now (no auth, CORS, or `/healthz` — see roadmap for planned additions). YAML hot-reload
(`YAML_AUTO_RELOAD`) works in HTTP mode but is best-effort when multiple clients are
connected concurrently.

### Using it from an MCP client

Any MCP client that speaks stdio works. Example configuration (Claude Desktop /
`mcp.json` style):

```json
{
  "mcpServers": {
    "ibmi-lite": {
      "command": "java",
      "args": [
        "-jar", "/path/to/ibmi-mcp-server-lite-0.1.0.jar",
        "--tools", "/path/to/tools/sample-tools.yaml"
      ],
      "env": {
        "DB2i_HOST": "myibmi.example.com",
        "DB2i_USER": "myuser",
        "DB2i_PASS": "..."
      }
    }
  }
}
```

## Defining tools in YAML

The format is the IBM i MCP Server YAML tool schema — `sources` (connections), `tools`
(parameterized SQL), and `toolsets` (groupings):

```yaml
sources:
  ibmi-system:
    host: ${DB2i_HOST}
    user: ${DB2i_USER}
    password: ${DB2i_PASS}
    port: 8076
    ignore-unauthorized: true

tools:
  active_job_info:
    source: ibmi-system
    description: "Find the top CPU consumers"
    parameters:
      - name: limit
        type: integer
        default: 10
        min: 1
        max: 100
    statement: |
      SELECT CPU_TIME, A.* FROM TABLE(QSYS2.ACTIVE_JOB_INFO()) A
      ORDER BY CPU_TIME DESC FETCH FIRST :limit ROWS ONLY

toolsets:
  performance:
    tools: [active_job_info]
```

Key semantics (full details in [docs/yaml-tools-reference.md](docs/yaml-tools-reference.md)):

- `${VAR}` placeholders are substituted from the environment (a `.env` file is read if
  present; real environment variables win) before YAML parsing.
- `:name` placeholders become **parameterized queries** — values are never spliced into
  the SQL text. Array parameters expand to one `?` per element for `IN (:list)` clauses.
- Tools are **read-only by default**: only SELECT/WITH statements pass validation unless
  a tool sets `security.readOnly: false`.
- `--toolsets a,b` (or `SELECTED_TOOLSETS`) registers only the tools in those toolsets.

### Built-in `execute_sql` tool (opt-in)

The reference server ships an ad-hoc `execute_sql` escape hatch for exploration and
text-to-SQL workflows. The lite server registers the same built-in when enabled:

```bash
# CLI (wins over env)
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml --execute-sql

# Or via .env / process env
IBMI_ENABLE_EXECUTE_SQL=true
IBMI_EXECUTE_SQL_READONLY=true   # default; set false to allow writes
```

When enabled, `execute_sql` appears in `tools/list` alongside YAML tools. It accepts a
single required `sql` string, uses direct substitution (`:sql` → verbatim SQL), and
re-validates the substituted statement at call time (read-only by default: only
`SELECT`/`WITH` pass). If multiple sources are defined, the first source key in YAML
merge order is used.

Do not define a YAML tool named `execute_sql`. When the built-in is enabled, it is
registered programmatically and takes precedence over any same-named YAML entry.

- **Hot-reload** (default on): when any resolved tools YAML file changes on disk, the server
  re-merges and updates the live tool registry without restarting. See
  [Hot-reloading tools YAML](#hot-reloading-tools-yaml) below.

## Hot-reloading tools YAML

When `YAML_AUTO_RELOAD` is enabled (the default), the server watches every YAML file
resolved from `--tools` (file, directory, or glob) and live-updates the MCP tool registry
on save — `addTool` / `removeTool` followed by `notifications/tools/list_changed` so
connected clients re-fetch `tools/list`. Reload re-runs the same merge path as startup
(`YAML_MERGE_*` flags apply).

```bash
# Start the server and leave it running (logs go to stderr)
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml
```

Edit `tools/sample-tools.yaml` in your editor and save. On stderr you should see:

```
YAML file(s) changed: .../tools/sample-tools.yaml
Reloaded tool 'my_new_tool' ...
YAML reload applied: 0 removed, 1 added
```

**Validate YAML before relying on reload** — a bad save is logged and the previous tool
set is kept:

```bash
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml --list-tools
```

**Disable hot-reload** with `--no-reload` or `YAML_AUTO_RELOAD=false` in `.env`.

**Limits (by design):** DB **sources** are loaded at startup only — adding a new source in
YAML on reload will fail validation until the server is restarted. Tools must be defined
under the top-level `tools:` key (not nested inside another tool).

**Manual testing note:** `scripts/smoke-test.py` and `sandbox/mcp-cli` spawn a **new**
server per invocation, so they exercise startup loading but not hot-reload. To test
reload, keep one server process running (as above) or use an MCP client (Cursor, Claude
Desktop) that holds the stdio session open.

## CLI and environment reference

| Flag | Env var | Description |
|---|---|---|
| `-t, --tools <path>` | `TOOLS_YAML_PATH` | Tools YAML file, directory, or glob (required) |
| `-ts, --toolsets <a,b>` | `SELECTED_TOOLSETS` | Only register tools in these toolsets |
| `--list-toolsets` | — | Print toolsets and exit |
| `--list-tools` | — | Print all enabled tools and exit |
| `--no-reload` | `YAML_AUTO_RELOAD` | Disable hot-reload of tools YAML (env default: on) |
| `--execute-sql` | `IBMI_ENABLE_EXECUTE_SQL` | Register the built-in `execute_sql` tool (CLI wins; default off) |
| — | `IBMI_EXECUTE_SQL_READONLY` | Read-only mode for `execute_sql` (default on: `true` or `1`) |
| `--env-file <path>` | — | `.env` file for `${VAR}` interpolation (default `./.env`) |
| `--version` / `--help` | — | Print and exit |
| — | `MCP_LOG_LEVEL` | `debug`, `info` (default), `warn`, `error` — logs go to **stderr** |
| — | `YAML_MERGE_ARRAYS` | `true` (default) — concatenate toolset `tools` arrays on name collision |
| — | `YAML_ALLOW_DUPLICATE_TOOLS` | `false` (default) — error on duplicate tool names across merged files |
| — | `YAML_ALLOW_DUPLICATE_SOURCES` | `false` (default) — error on duplicate source names across merged files |
| — | `YAML_VALIDATE_MERGED` | `true` (default) — post-merge tool→source and toolset→tool checks |

`YAML_AUTO_RELOAD` is read from the merged environment (`.env` file plus process env;
process env wins). Enabled when unset, or when the value is `true` or `1`.

## Project layout

| Package | Responsibility |
|---|---|
| `com.ibm.ibmi.mcp.config` | YAML model records + loader (`${VAR}` interpolation, validation) |
| `com.ibm.ibmi.mcp.schema` | Parameter definitions → MCP `inputSchema` (JSON Schema) |
| `com.ibm.ibmi.mcp.sql` | `:name` → parameterized-query binding; basic SQL security validation |
| `com.ibm.ibmi.mcp.mapepire` | One lazy Mapepire `Pool` per source (`SourceManager`) |
| `com.ibm.ibmi.mcp.server` | MCP server construction, tool registration, hot-reload watcher, call handling |
| `packaging/`, `Makefile` | IBM i RPM packaging (spec, PASE launcher, Service Commander unit) |

Tool results mirror the reference server's `StandardSqlToolOutput` shape, returned as a
JSON text block and as MCP `structuredContent`:

```json
{
  "success": true,
  "data": [ { "JOB_NAME": "...", "CPU_TIME": 123 } ],
  "metadata": { "toolName": "active_job_info", "rowCount": 3, "executionTime": 1100,
                "columns": [{ "name": "JOB_NAME", "type": "VARCHAR", "label": "JOB_NAME" }],
                "parameterMode": "parameters", "parameterCount": 1 }
}
```

## Testing

- `./mvnw test` — unit tests for the YAML loader, schema generation, parameter binding,
  security validation, and graceful shutdown (no IBM i required).
- `./mvnw verify -Pintegration-tests` — Java pipeline integration tests
  (`SourceManager` → `SqlToolHandler`) against a live Mapepire (needs `.env`; skipped,
  not failed, when `DB2i_*` are missing). See
  [docs/running-on-ibmi.md](docs/running-on-ibmi.md#junit-integration-tests-live-mapepire).
- `python3 scripts/smoke-test.py` — full-protocol test against a live IBM i (needs `.env`).

## Running on IBM i

The fat jar is the deployment unit. See [docs/running-on-ibmi.md](docs/running-on-ibmi.md)
for deployment steps, the PASE launcher, the RPM build pipeline
(`.github/workflows/rpm-ibmi.yml`, `packaging/rpm/*.spec`), and the supported **Java 17
runtime on IBM i** — IBM Technology for Java 17 (5770-JV1 option 20). Running the server
anywhere else against an IBM i also works today, as the smoke test demonstrates.

## What's deliberately missing

This MVP implements a faithful subset of the reference server. Auth/CORS on HTTP,
`GET /healthz`, the full SQL security parser, and more are sequenced into milestones —
each tracked as a GitHub issue with pointers into the reference implementation — in the
[**roadmap**](ROADMAP.md)
([milestones](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestones) ·
[good first issues](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues?q=is%3Aopen+label%3A%22good+first+issue%22)).
