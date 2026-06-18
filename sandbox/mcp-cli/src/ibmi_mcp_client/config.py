"""Locate and launch the ibmi-mcp-server-lite MCP server over stdio.

The Java fat jar speaks MCP over stdio (JSON-RPC). We spawn it with FastMCP's
``StdioTransport`` and point it at the sample tools YAML. Credentials for the
IBM i / Db2 backend are read by the *server* from the lite project's ``.env``
file (via ``--env-file``), so no secrets pass through this client.

Paths are auto-discovered from the surrounding ibmi-mcp-server-lite checkout,
but each can be overridden with an environment variable:

    IBMI_MCP_HOME       root of the ibmi-mcp-server-lite project
    IBMI_MCP_JAR        path to the server fat jar
    IBMI_MCP_TOOLS      path to the tools YAML
    IBMI_MCP_ENV_FILE   .env consumed by ${VAR} interpolation in the tools YAML
"""

from __future__ import annotations

import os
from pathlib import Path

from fastmcp import Client
from fastmcp.client.transports import StdioTransport


def _discover_lite_root() -> Path:
    """Find the ibmi-mcp-server-lite root by walking up from this file."""
    for parent in Path(__file__).resolve().parents:
        if (parent / "pom.xml").is_file() and (parent / "tools" / "sample-tools.yaml").is_file():
            return parent
    # Fallback: <root>/sandbox/mcp-server-test/src/ibmi_mcp_client/config.py
    return Path(__file__).resolve().parents[4]


LITE_ROOT = Path(os.environ.get("IBMI_MCP_HOME") or _discover_lite_root())
SERVER_JAR = Path(
    os.environ.get("IBMI_MCP_JAR")
    or LITE_ROOT / "target" / "ibmi-mcp-server-lite-0.1.0.jar"
)
TOOLS_YAML = Path(os.environ.get("IBMI_MCP_TOOLS") or LITE_ROOT / "tools" / "sample-tools.yaml")
ENV_FILE = Path(os.environ.get("IBMI_MCP_ENV_FILE") or LITE_ROOT / ".env")


def build_transport(
    log_level: str | None = None,
    tools: str | Path | None = None,
) -> StdioTransport:
    """Build a stdio transport that spawns the server fat jar.

    ``tools`` overrides the tools YAML the server loads (defaults to ``TOOLS_YAML``).
    ``log_level`` overrides the server's ``MCP_LOG_LEVEL`` (e.g. ``"error"`` to
    silence the JVM's chatty stderr logs); ``None`` keeps the server's own default.
    """
    # Resolve a user-supplied --tools relative to the caller's cwd, then pass it
    # absolute: the server runs with cwd=LITE_ROOT, so a relative path would resolve
    # against the wrong directory.
    tools_path = Path(tools).resolve() if tools else TOOLS_YAML
    if not SERVER_JAR.is_file():
        raise FileNotFoundError(
            f"Server jar not found: {SERVER_JAR}\n"
            f"Build it first:  (cd {LITE_ROOT} && ./mvnw package)\n"
            f"or set IBMI_MCP_JAR to an existing jar."
        )
    if not tools_path.is_file():
        raise FileNotFoundError(f"Tools YAML not found: {tools_path}")
    args = ["-jar", str(SERVER_JAR), "--tools", str(tools_path)]
    if ENV_FILE.is_file():
        args += ["--env-file", str(ENV_FILE)]
    # Process env wins over the .env file, so this overrides MCP_LOG_LEVEL there.
    env = {**os.environ, "MCP_LOG_LEVEL": log_level} if log_level else None
    return StdioTransport(command="java", args=args, cwd=str(LITE_ROOT), env=env)


def make_client(
    log_level: str | None = None,
    tools: str | Path | None = None,
) -> Client:
    """A FastMCP client wired to a freshly spawned ibmi-mcp-server-lite process."""
    return Client(build_transport(log_level=log_level, tools=tools))
