"""Lightweight CLI for the ibmi-mcp-server-lite MCP server.

    ibmi-mcp list                                       # list tools + parameters
    ibmi-mcp list --json                                # machine-readable
    ibmi-mcp list --tools path/to/tools.yaml            # point at a different tools file
    ibmi-mcp call active_job_info limit=3               # name=value arguments
    ibmi-mcp call list_user_libraries library_pattern=QSYS%
    ibmi-mcp call active_job_info --args-json '{"limit": 3}'
    ibmi-mcp call active_job_info limit=3 --json        # raw JSON result (pipeable)

Argument values from ``name=value`` pairs are coerced using the tool's input
schema (integer/number/boolean/array); use ``--args-json`` for full control.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Any

from ibmi_mcp_client.config import make_client
from ibmi_mcp_client.output import parse_output, text_content


def _coerce(value: str, schema_type: str | None) -> Any:
    if schema_type == "integer":
        return int(value)
    if schema_type == "number":
        return float(value)
    if schema_type == "boolean":
        return value.strip().lower() in {"1", "true", "yes", "on"}
    if schema_type == "array":
        return [item for item in value.split(",") if item != ""]
    return value


def _parse_pairs(pairs: list[str], schema: dict[str, Any]) -> dict[str, Any]:
    properties = (schema or {}).get("properties", {})
    arguments: dict[str, Any] = {}
    for pair in pairs:
        if "=" not in pair:
            raise SystemExit(f"error: argument '{pair}' must be in name=value form")
        name, value = pair.split("=", 1)
        arguments[name] = _coerce(value, properties.get(name, {}).get("type"))
    return arguments


def _log_level(verbose: bool) -> str | None:
    return None if verbose else "error"


async def run_list(as_json: bool, verbose: bool, tools_file: str | None = None) -> int:
    async with make_client(log_level=_log_level(verbose), tools=tools_file) as client:
        tools = await client.list_tools()

    if as_json:
        print(json.dumps(
            [
                {"name": t.name, "description": t.description, "inputSchema": t.inputSchema}
                for t in tools
            ],
            indent=2,
        ))
        return 0

    for tool in tools:
        required = tool.inputSchema.get("required", [])
        properties = tool.inputSchema.get("properties", {})
        print(tool.name)
        if tool.description:
            print(f"    {tool.description}")
        for name, spec in properties.items():
            flag = "required" if name in required else "optional"
            default = f", default={spec['default']}" if "default" in spec else ""
            print(f"    - {name}: {spec.get('type', '?')} ({flag}{default})")
        print()
    return 0


async def run_call(
    tool: str,
    pairs: list[str],
    args_json: str | None,
    as_json: bool,
    verbose: bool,
    tools_file: str | None = None,
) -> int:
    async with make_client(log_level=_log_level(verbose), tools=tools_file) as client:
        if args_json is not None:
            try:
                arguments = json.loads(args_json)
            except json.JSONDecodeError as exc:
                print(f"error: --args-json is not valid JSON: {exc}", file=sys.stderr)
                return 2
            if not isinstance(arguments, dict):
                print("error: --args-json must be a JSON object", file=sys.stderr)
                return 2
        else:
            tools = {t.name: t for t in await client.list_tools()}
            schema = tools[tool].inputSchema if tool in tools else {}
            arguments = _parse_pairs(pairs, schema)

        try:
            result = await client.call_tool(tool, arguments, raise_on_error=False)
        except Exception as exc:  # unknown tool, transport error, ...
            print(f"error: {exc}", file=sys.stderr)
            return 1

        if result.is_error:
            print(text_content(result), file=sys.stderr)
            return 1

        payload = parse_output(result)

    if as_json:
        print(json.dumps(payload, indent=2, default=str))
        return 0

    metadata = payload.get("metadata", {})
    print(
        f"success={payload.get('success')} "
        f"rows={metadata.get('rowCount')} "
        f"executionTime(ms)={metadata.get('executionTime')}"
    )
    for row in payload.get("data", []):
        print(json.dumps(row, default=str))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ibmi-mcp",
        description="Lightweight MCP client for ibmi-mcp-server-lite.",
    )
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "-v", "--verbose", action="store_true", help="show the server's stderr logs"
    )
    common.add_argument(
        "-t", "--tools", metavar="PATH",
        help="tools YAML for the server to load (default: sample-tools.yaml)",
    )

    sub = parser.add_subparsers(dest="command", required=True)

    p_list = sub.add_parser("list", parents=[common], help="list available tools")
    p_list.add_argument("--json", action="store_true", help="output raw JSON")

    p_call = sub.add_parser("call", parents=[common], help="run a tool")
    p_call.add_argument("tool", help="tool name")
    p_call.add_argument("args", nargs="*", help="arguments as name=value")
    p_call.add_argument(
        "--args-json", metavar="JSON", help="arguments as a JSON object (instead of name=value)"
    )
    p_call.add_argument("--json", action="store_true", help="output the raw JSON result")

    return parser


def main(argv: list[str] | None = None) -> int:
    ns = build_parser().parse_args(argv)
    try:
        if ns.command == "list":
            return asyncio.run(run_list(ns.json, ns.verbose, ns.tools))
        if ns.command == "call":
            return asyncio.run(
                run_call(ns.tool, ns.args, ns.args_json, ns.json, ns.verbose, ns.tools)
            )
    except FileNotFoundError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # server failed to start / connect — keep output clean
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
