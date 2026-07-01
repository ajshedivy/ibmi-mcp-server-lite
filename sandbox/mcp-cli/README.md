# mcp-cli

A minimal MCP client CLI for poking at the [`ibmi-mcp-server-lite`](../../) server by
hand — list its tools and run them from the terminal. Built on
[FastMCP](https://gofastmcp.com); it spawns the server's Java fat jar over stdio and
speaks the MCP protocol to it.

```
┌──────────────────────┐  stdio (JSON-RPC)  ┌──────────────────────┐  wss://:8076  ┌──────────┐
│ ibmi-mcp (this CLI)  │ ◄────────────────► │ ibmi-mcp-server-lite │ ◄───────────► │ IBM i Db2│
└──────────────────────┘                    │  (java -jar ...)     │   mapepire    │          │
                                            └──────────────────────┘               └──────────┘
```

## Prerequisites

- **Python 3.10+** and [`uv`](https://docs.astral.sh/uv/)
- **Java 17+** on `PATH`
- The server jar built in the lite project (`cd ../.. && ./mvnw package`)
- A reachable IBM i with Mapepire, configured in the lite project's `.env` — the CLI
  points the server at that file automatically (no credentials are read by this CLI).

## Install

```bash
cd sandbox/mcp-cli
uv sync
```

## Usage

```bash
uv run ibmi-mcp list                                  # tools + parameters
uv run ibmi-mcp list --json                           # machine-readable

uv run ibmi-mcp call active_job_info limit=3          # name=value arguments
uv run ibmi-mcp call list_user_libraries library_pattern=QSYS%
uv run ibmi-mcp call active_job_info --args-json '{"limit": 3}'
uv run ibmi-mcp call active_job_info limit=3 --json   # raw JSON result

uv run ibmi-mcp list --tools path/to/other-tools.yaml # load a different tools file
```

- `--tools/-t PATH` points the server at a different tools YAML (default:
  the lite project's `tools/sample-tools.yaml`).
- Each `list` / `call` spawns a **new** server process — handy for one-shot tests, but
  not for exercising **hot-reload**. To test reload, run the jar directly and leave it
  running while editing the YAML (see the main [README](../../README.md#hot-reloading-tools-yaml)).
- `name=value` argument values are coerced from the tool's input schema
  (`integer`/`number`/`boolean`/`array`); use `--args-json` for full control.
- Default `call` output is one JSON row per line (NDJSON) — pipe to `jq`. `--json`
  emits the whole `{success, data, metadata}` payload.
- Exit codes: `0` success, `1` tool/connection error, `2` usage error.
- `-v/--verbose` shows the server's stderr logs (suppressed by default).

Also runnable as a module: `uv run python -m ibmi_mcp_client list`.

## Pointing at a different server / jar

Paths are auto-discovered from the surrounding lite checkout. Override with env vars:

| Variable | Default |
|---|---|
| `IBMI_MCP_HOME` | auto-detected lite project root |
| `IBMI_MCP_JAR` | `$IBMI_MCP_HOME/target/ibmi-mcp-server-lite-0.1.0.jar` |
| `IBMI_MCP_TOOLS` | `$IBMI_MCP_HOME/tools/sample-tools.yaml` (or `--tools`) |
| `IBMI_MCP_ENV_FILE` | `$IBMI_MCP_HOME/.env` |
