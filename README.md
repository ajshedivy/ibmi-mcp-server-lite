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
- [mapepire-java](https://github.com/Mapepire-IBMi/mapepire-java) 0.1.2
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

# 4. End-to-end smoke test over real stdio JSON-RPC (initialize → tools/list → tools/call)
python3 scripts/smoke-test.py
```

> **TLS note:** unlike the Node.js SDK, mapepire-java verifies the TLS *hostname* even with
> `ignore-unauthorized: true` (only certificate-chain trust is skipped). `DB2i_HOST` must
> match a name in the Mapepire server certificate's Subject Alternative Name. See
> [docs/running-on-ibmi.md](docs/running-on-ibmi.md#tls-hostname-verification).

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

## CLI and environment reference

| Flag | Env var | Description |
|---|---|---|
| `-t, --tools <path>` | `TOOLS_YAML_PATH` | Tools YAML file (required) |
| `-ts, --toolsets <a,b>` | `SELECTED_TOOLSETS` | Only register tools in these toolsets |
| `--list-toolsets` | — | Print toolsets and exit |
| `--env-file <path>` | — | `.env` file for `${VAR}` interpolation (default `./.env`) |
| `--version` / `--help` | — | Print and exit |
| — | `MCP_LOG_LEVEL` | `debug`, `info` (default), `warn`, `error` — logs go to **stderr** |

## Project layout

| Package | Responsibility |
|---|---|
| `com.ibm.ibmi.mcp.config` | YAML model records + loader (`${VAR}` interpolation, validation) |
| `com.ibm.ibmi.mcp.schema` | Parameter definitions → MCP `inputSchema` (JSON Schema) |
| `com.ibm.ibmi.mcp.sql` | `:name` → parameterized-query binding; basic SQL security validation |
| `com.ibm.ibmi.mcp.mapepire` | One lazy `SqlJob` per source (`SourceManager`) |
| `com.ibm.ibmi.mcp.server` | MCP server construction, tool registration, call handling |
| `packaging/`, `Makefile` | IBM i RPM skeleton (spec, PASE launcher, Service Commander unit) |

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
  and security validation (no IBM i required).
- `python3 scripts/smoke-test.py` — full-protocol test against a live IBM i (needs `.env`).

## Running on IBM i

The fat jar is the deployment unit. See [docs/running-on-ibmi.md](docs/running-on-ibmi.md)
for deployment steps, the PASE launcher, the RPM build skeleton
(`.github/workflows/rpm-ibmi.yml`, `packaging/rpm/*.spec`), and the current **Java 17
runtime gap on IBM i** — the one open blocker for running the server on the system itself
(running it anywhere else against an IBM i works today, as the smoke test demonstrates).

## What's deliberately missing

This MVP implements a faithful subset of the reference server. HTTP transport, connection
pooling, markdown result formatting, multi-file YAML merge, hot reload, the full SQL
security parser, and more are sequenced into milestones — each tracked as a GitHub issue
with pointers into the reference implementation — in the [**roadmap**](ROADMAP.md)
([milestones](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestones) ·
[good first issues](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues?q=is%3Aopen+label%3A%22good+first+issue%22)).
