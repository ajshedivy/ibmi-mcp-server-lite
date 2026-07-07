#!/usr/bin/env python3
"""Smoke test: drives the stdio MCP server with raw JSON-RPC.

Sends initialize -> notifications/initialized -> tools/list -> tools/call
and prints each response. Requires a .env (or real environment) with the
DB2i_* variables used by the tools YAML.

Usage: python3 scripts/smoke-test.py [path/to/server.jar] [path/to/tools.yaml] [--execute-sql]

With --execute-sql, also exercises execute_sql read-only rejection (no live DB
required) and attempts a SELECT (needs a working Mapepire connection).
"""
import json
import subprocess
import sys
import threading

args = sys.argv[1:]
enable_execute_sql = "--execute-sql" in args
if enable_execute_sql:
    args.remove("--execute-sql")

JAR = args[0] if len(args) > 0 else "target/ibmi-mcp-server-lite-0.1.0.jar"
TOOLS = args[1] if len(args) > 1 else "tools/sample-tools.yaml"

server_cmd = ["java", "-jar", JAR, "--tools", TOOLS, "--transport", "stdio", "--no-reload"]
if enable_execute_sql:
    server_cmd.append("--execute-sql")

proc = subprocess.Popen(
    server_cmd,
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

# surface server logs (stderr) without blocking
threading.Thread(target=lambda: [print("[server]", l, end="", file=sys.stderr) for l in proc.stderr],
                 daemon=True).start()


def send(msg):
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()


def recv(expect_id):
    # Skip server-initiated notifications (e.g. tools/list_changed from addTool)
    # and wait for the response matching the request id.
    while True:
        line = proc.stdout.readline()
        if not line:
            raise SystemExit("server closed stdout")
        msg = json.loads(line)
        if msg.get("id") == expect_id:
            if "error" in msg:
                raise SystemExit(f"JSON-RPC error: {msg['error']}")
            return msg


def parse_tool_payload(result):
    """Return structured tool output, or None when only plain-text error is available."""
    structured = result.get("structuredContent")
    if isinstance(structured, dict):
        return structured

    text = result.get("content", [{}])[0].get("text", "")
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def print_tool_result(label, result):
    print(f"== {label} ==")
    print("isError:", result.get("isError"))
    payload = parse_tool_payload(result)
    if payload is not None:
        success = payload.get("success")
        print("success:", success)
        if success:
            metadata = payload.get("metadata", {})
            print("rows:", metadata.get("rowCount"),
                  "| executionTime(ms):", metadata.get("executionTime"))
            for row in payload.get("data", []):
                print(" ", row)
        else:
            print("error:", payload.get("error"))
    else:
        print(result.get("content", [{}])[0].get("text", "(no content)"))


send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {"name": "smoke-test", "version": "0.0.1"}}})
init = recv(1)
print("== initialize ==")
print(json.dumps(init["result"]["serverInfo"], indent=2))

send({"jsonrpc": "2.0", "method": "notifications/initialized"})

send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
tools = recv(2)["result"]["tools"]
print(f"== tools/list ({len(tools)} tools) ==")
for t in tools:
    print(f"  {t['name']}: required={t['inputSchema'].get('required', [])}")

if enable_execute_sql:
    tool_names = {t["name"] for t in tools}
    if "execute_sql" not in tool_names:
        raise SystemExit("execute_sql not in tools/list (expected with --execute-sql)")

send({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
    "name": "active_job_info", "arguments": {"limit": 3}}})
print_tool_result("tools/call active_job_info(limit=3)", recv(3)["result"])

send({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {
    "name": "list_user_libraries", "arguments": {"library_pattern": "QSYS2%"}}})
print_tool_result("tools/call list_user_libraries(library_pattern='QSYS2%')", recv(4)["result"])

# error path: missing required parameter
send({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {
    "name": "list_user_libraries", "arguments": {}}})
print_tool_result("tools/call list_user_libraries(missing arg)", recv(5)["result"])

if enable_execute_sql:
    # Read-only rejection is validated before any DB connection.
    send({"jsonrpc": "2.0", "id": 6, "method": "tools/call", "params": {
        "name": "execute_sql",
        "arguments": {"sql": "DELETE FROM SAMPLE.EMPLOYEE"}}})
    delete_result = recv(6)["result"]
    print_tool_result("tools/call execute_sql(DELETE ...)", delete_result)
    delete_payload = parse_tool_payload(delete_result)
    if not delete_result.get("isError") or delete_payload is None or delete_payload.get("success"):
        raise SystemExit("execute_sql DELETE should be rejected in read-only mode")
    error = str(delete_payload.get("error", ""))
    if "read-only" not in error.lower() and "delete" not in error.lower():
        raise SystemExit(f"unexpected execute_sql DELETE error: {error}")

    send({"jsonrpc": "2.0", "id": 7, "method": "tools/call", "params": {
        "name": "execute_sql",
        "arguments": {"sql": "SELECT 1 AS ONE FROM SYSIBM.SYSDUMMY1"}}})
    print_tool_result("tools/call execute_sql(SELECT 1 ...)", recv(7)["result"])

proc.terminate()
print("SMOKE TEST PASSED")
