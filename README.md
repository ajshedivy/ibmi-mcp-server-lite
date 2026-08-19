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

# 3. Sanity check: list the toolsets defined in the vendored packs
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools --list-toolsets
#    Requires YAML_ALLOW_DUPLICATE_SOURCES=true (set in .env.example). See tools/README.md.

# 4. End-to-end smoke test over real stdio JSON-RPC (initialize → tools/list → tools/call)
python3 scripts/smoke-test.py
#    Optional: --builtin-tools and/or --execute-sql to exercise those gates
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
  --tools tools \
  --transport http
```

Defaults: bind `0.0.0.0:3010`, MCP endpoint `/mcp`. Override via flags or environment:

| Flag | Environment variable | Default |
|------|---------------------|---------|
| `--transport http` | `MCP_TRANSPORT_TYPE` | `stdio` |
| `--http-port` | `MCP_HTTP_PORT` | `3010` |
| `--http-host` | `MCP_HTTP_HOST` | `0.0.0.0` |
| `--http-endpoint` | `MCP_HTTP_ENDPOINT_PATH` | `/mcp` |
| (env only) | `MCP_ALLOWED_ORIGINS` | empty (see CORS below) |
| (env only) | `MCP_SERVER_ENV` | empty (non-production) |

CLI flags win over environment variables. The HTTP transport is **unauthenticated** for
now (auth is still on the roadmap). CORS is enabled via Jetty `CrossOriginHandler`:

| `MCP_ALLOWED_ORIGINS` | `MCP_SERVER_ENV` | Browser CORS |
|----------------------|------------------|--------------|
| sole `*` | any | allow any Origin |
| non-empty CSV (no `*`) | any | only listed origins |
| `*` mixed with other origins | any | startup error |
| empty / unset | `production` | deny all |
| empty / unset | anything else | allow any Origin (dev) |

`MCP_SERVER_ENV=production` fail-closes CORS when no origin list is set and
refuses startup when a populated `.env` or secret-bearing YAML grants group/world
permissions. See
[docs/running-on-ibmi.md](docs/running-on-ibmi.md#secret-file-permissions).

Example: `MCP_ALLOWED_ORIGINS=http://localhost:5173`. Use `*` alone for allow-any;
do not mix `*` with specific origins. Native clients (curl) that omit
`Origin` are unaffected. `GET /healthz` returns JSON pool health
(`status` is `ok` or `degraded`; HTTP status is always 200 — probes should read the body).
It reflects **cached pool state** after a connect attempt (or successful query) — it does
**not** probe Mapepire. Until a tool has touched a source, `pools` is `{}` and status stays
`ok` even if Mapepire is down. After a failed connect or eviction, `unhealthy` stays sticky
while a reconnect is in progress (`connecting: true`) so status remains `degraded` until
init succeeds or fails again.
YAML hot-reload (`YAML_AUTO_RELOAD`) works in HTTP mode but is best-effort when multiple
clients are connected concurrently.

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
        "--tools", "/path/to/tools"
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

### Built-in schema discovery tools

The reference server’s text-to-SQL discovery chain is available as Java built-ins
(same `SqlToolHandler` path as YAML tools — output shape `{success, data, metadata}`):

| Tool | Gate |
|---|---|
| `describe_sql_object` | **Always on** when YAML sources exist (`QSYS2.GENERATE_SQL`) |
| `list_schemas`, `list_tables_in_schema`, `get_table_columns`, `get_related_objects`, `validate_query` | `--builtin-tools` / `IBMI_ENABLE_DEFAULT_TOOLS=true` |
| `execute_sql` | `--execute-sql` / `IBMI_ENABLE_EXECUTE_SQL=true` (separate; not auto-enabled by `--builtin-tools`) |

Intended agent flow: `list_schemas` → `list_tables_in_schema` → `get_table_columns` →
`get_related_objects` → `validate_query` → `execute_sql`.

`describe_sql_object` reports a missing object as an error naming the object and library it
searched, rather than as a successful empty result — `QSYS2.GENERATE_SQL` returns no rows
instead of raising, and `object_library` defaults to `QSYS2`, so looking in the wrong library
is easy to do. The discovery tools that filter still return an empty result as a success.

```bash
# Discovery pack only
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools --builtin-tools

# Full chain (discovery + ad-hoc SQL)
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools --builtin-tools --execute-sql

# Or via .env / process env
IBMI_ENABLE_DEFAULT_TOOLS=true
IBMI_ENABLE_EXECUTE_SQL=true
IBMI_EXECUTE_SQL_READONLY=true   # default; set false to allow writes
```

`execute_sql` accepts a single required `sql` string, uses direct substitution
(`:sql` → verbatim SQL), and re-validates at call time (read-only by default: only
`SELECT`/`WITH` pass). Built-ins use the first source key in YAML merge order when
multiple sources are defined.

**Name collisions:** if a YAML tool shares a name with a registering built-in, the YAML
tool is skipped and a warning is logged (builtins win). With `--builtin-tools`, this
affects vendored `list_tables_in_schema` and `validate_query` in
`tools/developer/text2sql.yaml`. A YAML tool is only skipped when a built-in actually
takes its place — with no `sources:` block no built-in registers, so nothing is filtered.

**Row limits:** `describe_sql_object`, `get_table_columns`, and `get_related_objects` page
the full result set (up to 30000 rows) so multi-line DDL and wide tables come back intact.
`list_schemas` and `list_tables_in_schema` page in SQL and fetch up to their `limit`
maximum of 500. Any result that hits a cap sets `metadata.truncated: true`.

**Security note:** `describe_sql_object` is registered whenever sources exist and cannot be
switched off short of removing the `sources:` block. `QSYS2.GENERATE_SQL` reproduces the DDL
of any supported object type, including `MASK` and `PERMISSION` (row and column access
control rules) and `PROCEDURE`/`FUNCTION` bodies. Db2 still enforces object authority
against the configured `DB2i_USER`, so nothing is reachable that this user could not
already read — size that user's authority accordingly. Note also that the Streamable HTTP
transport has **no authentication** and `MCP_HTTP_HOST` defaults to `0.0.0.0`; bind it to
`127.0.0.1` or put it behind an authenticating proxy.

- **Hot-reload** (default on): when any resolved YAML file changes on disk, the server
  re-merges and updates live sources, tools, and `toolsets://` resources without restarting.
  See [Hot-reloading YAML sources, tools, and resources](#hot-reloading-yaml-sources-tools-and-resources)
  below.

## Hot-reloading YAML sources, tools, and resources

When `YAML_AUTO_RELOAD` is enabled (the default), the server watches every YAML file
resolved from `--tools` (file, directory, or glob) and live-updates sources, the MCP
tool registry, and `toolsets://` catalog/detail resources on save. Reload re-runs the same
merge path as startup (`YAML_MERGE_*` flags apply), then sends
`notifications/tools/list_changed` so connected clients re-fetch `tools/list`. Resource
URI add/remove/refresh already emits `notifications/resources/list_changed`.

Source changes are applied by name:

- unchanged source configuration keeps its current Mapepire pool;
- added sources are registered lazily and connect on first use;
- updated or removed sources wait up to the pool shutdown grace for in-flight queries,
  then close the old pool. The next call to an updated source creates a pool with the new
  host, credentials, sizes, timeouts, and `jdbc-options`.

```bash
# Start the server and leave it running (logs go to stderr)
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools
```

Edit a YAML file under `tools/` in your editor and save. On stderr you should see:

```
YAML file(s) changed: .../tools/performance/performance.yaml
Reloaded tool 'my_new_tool' ...
YAML reload applied: sources +0 ~0 -0; tools -0 +1
```

**Validate YAML before relying on reload** — a bad save is logged and the previous source
configuration, tool set, and `toolsets://` URIs are kept:

```bash
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools --list-tools
```

**Disable hot-reload** with `--no-reload` or `YAML_AUTO_RELOAD=false` in `.env`.

**HTTP behavior:** reload keeps the existing Jetty server, MCP server, and client sessions.
It is best-effort for concurrent calls: an in-flight call may finish on the old pool, or
fail if it exceeds the drain grace and the pool closes. Subsequent calls use the new source
configuration.

**Limits (by design):** authentication token pools are not reloaded; issued tokens remain
valid until expiry. Tools must be defined under the top-level `tools:` key (not nested
inside another tool).

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
| `--no-reload` | `YAML_AUTO_RELOAD` | Disable YAML source/tool hot-reload (env default: on) |
| `--builtin-tools` | `IBMI_ENABLE_DEFAULT_TOOLS` | Register built-in schema discovery tools (CLI wins; default off) |
| `--execute-sql` | `IBMI_ENABLE_EXECUTE_SQL` | Register the built-in `execute_sql` tool (CLI wins; default off) |
| — | `IBMI_EXECUTE_SQL_READONLY` | Read-only mode for `execute_sql` (default on: `true` or `1`) |
| `--env-file <path>` | — | `.env` file for `${VAR}` interpolation (default `./.env`) |
| `--version` / `--help` | — | Print and exit |
| — | `MCP_LOG_LEVEL` | `debug`, `info` (default), `warn`, `error` — logs go to **stderr** |
| — | `MCP_POOL_IDLE_TIMEOUT_MS` | Close idle Mapepire pools after this many ms (default `300000`; `0` disables). YAML `mcp-pool-idle-timeout-ms` overrides |
| — | `MCP_POOL_QUERY_TIMEOUT_MS` | Fail execute/fetch after this many ms and evict the pool (default `30000`; `0` disables). YAML `mcp-pool-query-timeout-ms` overrides |
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
- `python3 scripts/smoke-test.py [--builtin-tools] [--execute-sql]` — full-protocol test against a live IBM i (needs `.env`).

## Running on IBM i

The fat jar is the deployment unit. See [docs/running-on-ibmi.md](docs/running-on-ibmi.md)
for deployment steps, the PASE launcher, the RPM build pipeline
(`.github/workflows/rpm-ibmi.yml`, `packaging/rpm/*.spec`), and the supported **Java 17
runtime on IBM i** — IBM Technology for Java 17 (5770-JV1 option 20). Running the server
anywhere else against an IBM i also works today, as the smoke test demonstrates.

## What's deliberately missing

This MVP implements a faithful subset of the reference server. Auth on HTTP,
the full SQL security parser, and more are sequenced into milestones —
each tracked as a GitHub issue with pointers into the reference implementation — in the
[**roadmap**](ROADMAP.md)
([milestones](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestones) ·
[good first issues](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues?q=is%3Aopen+label%3A%22good+first+issue%22)).
