# Contributing

Thanks for your interest in `ibmi-mcp-server-lite`. This guide gets the server building
and running on your machine and covers the conventions we expect in a pull request.

This is a minimal Java reimplementation of a **subset** of the
[IBM i MCP Server](https://github.com/IBM/ibmi-mcp-server) (Node.js). That server is the
behavioral contract: when something here is ambiguous, match it. Features we have
deliberately left for later are sequenced into milestones in the [**roadmap**](ROADMAP.md)
and tracked as [GitHub issues](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues) —
a [good first issue](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues?q=is%3Aopen+label%3A%22good+first+issue%22)
is the place to start.

## Prerequisites

- **Java 17+** (the MCP SDK requires class-file 61). Java 17 or 21 both work.
  Check with `java -version`.
- **Maven** — not required globally; the repo ships the Maven Wrapper (`./mvnw`).
- **An IBM i with the [Mapepire daemon](https://mapepire-ibmi.github.io/guides/sysadmin/)**
  running (default port 8076). Only needed to run the server against a real system — the
  unit tests need no IBM i.
- **Python 3** — only for the end-to-end smoke test (`scripts/smoke-test.py`).

## Get it building

```bash
git clone https://github.com/ajshedivy/ibmi-mcp-server-lite.git
cd ibmi-mcp-server-lite

# Build the fat jar and run the unit tests. Output: target/ibmi-mcp-server-lite-0.1.0.jar
./mvnw package

# Unit tests only (no IBM i needed) — the fast inner loop
./mvnw test

# A single test class or method
./mvnw test -Dtest=ParameterProcessorTest
./mvnw test -Dtest=ParameterProcessorTest#bindsNamedParameters
```

There is no lint step. Tests are JUnit 5 via Surefire and cover the YAML loader, schema
generation, parameter binding, and security validation.

## Configure credentials

The tools YAML uses `${VAR}` placeholders that are interpolated from the environment
(a `.env` file is read if present; real environment variables win) **before** YAML
parsing.

```bash
cp .env.example .env
# then edit DB2i_HOST / DB2i_USER / DB2i_PASS
```

`.env` is gitignored — never commit credentials. Only `.env.example` (placeholder values)
is tracked.

> **TLS note:** unlike the Node.js SDK, mapepire-java verifies the TLS *hostname* even
> with `ignore-unauthorized: true` (only certificate-chain trust is skipped). `DB2i_HOST`
> must match a name in the Mapepire server certificate's Subject Alternative Name, or the
> connection fails. See
> [docs/running-on-ibmi.md](docs/running-on-ibmi.md#tls-hostname-verification).

## Run it locally

```bash
# Inspect config without connecting to IBM i
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml --list-toolsets

# Run the server (stdio JSON-RPC transport — it waits for an MCP client on stdin)
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools/sample-tools.yaml

# Full-protocol smoke test against a live IBM i (initialize -> tools/list -> tools/call).
# Needs a configured .env and the built jar, so package first.
python3 scripts/smoke-test.py
```

To drive it from an MCP client (Claude Desktop, an IDE, an agent), see the
[README](README.md#using-it-from-an-mcp-client) for an example `mcpServers` config.

## Project layout

One pipeline, one package per stage under `src/main/java/com/ibm/ibmi/mcp/`:

| Package | Responsibility |
|---|---|
| `config` | YAML model records + loader (`${VAR}` interpolation, validation) |
| `schema` | Parameter definitions -> MCP `inputSchema` (JSON Schema) |
| `sql` | `:name` -> parameterized-query binding; basic SQL security validation |
| `mapepire` | One lazy `SqlJob` per source (`SourceManager`) |
| `server` | MCP server construction, tool registration, call handling |

Supporting material: [`ROADMAP.md`](ROADMAP.md) (milestones + linked issues), `tools/`
(sample YAML), `scripts/` (smoke test), `docs/` (YAML reference, IBM i deployment, and
`docs/research/` notes on the reference semantics), `packaging/` + `Makefile` (the IBM i
RPM skeleton).

[AGENTS.md](AGENTS.md) (symlinked as `CLAUDE.md`) is the condensed architecture and
constraints reference for AI coding agents — it's a good quick map of the codebase for
humans too.

## Adding a tool

Tools are declarative — you usually don't touch Java to add one. Define it in a tools
YAML file (`sources`, `tools`, `toolsets`); the full schema is in
[docs/yaml-tools-reference.md](docs/yaml-tools-reference.md). Keep statements read-only
(SELECT/WITH) unless you explicitly need otherwise, and use `:name` placeholders for any
user input — they become parameterized-query binds and are never spliced into the SQL.

## Conventions

A few things that will come up in review:

- **stdout belongs to the MCP stdio transport.** All logging goes to stderr. Never add a
  `System.out.println` — use the slf4j logger.
- **Java 17 is the floor**, and **Jackson 2 only** (the `mcp-core` + `mcp-json-jackson2`
  artifacts — the bare `mcp` aggregator pulls Jackson 3 and conflicts with mapepire-sdk).
- **Match the existing style.** Surgical changes; touch only what the change requires.
- **Never edit `dependency-reduced-pom.xml`** — it's shade-plugin output and gitignored.

### Commits and pull requests

- **Sign off every commit** (Developer Certificate of Origin): `git commit -s`.
- **Keep commits atomic** — one logical change each, with a `type: description` subject
  (e.g. `feat: connection pooling`, `docs: fix roadmap link`). See `git log` for the
  established style.
- Make sure `./mvnw package` is green before opening a PR.
- Working from the [roadmap](ROADMAP.md)? Reference its issue in your PR (e.g. `Closes #12`)
  so the milestone updates automatically when it merges.
