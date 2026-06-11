#!/usr/bin/env python3
"""Smoke test: drives the stdio MCP server with raw JSON-RPC.

Sends initialize -> notifications/initialized -> tools/list -> tools/call
and prints each response. Requires a .env (or real environment) with the
DB2i_* variables used by the tools YAML.

Usage: python3 scripts/smoke-test.py [path/to/server.jar] [path/to/tools.yaml]
"""
import json
import subprocess
import sys
import threading

JAR = sys.argv[1] if len(sys.argv) > 1 else "target/ibmi-mcp-server-lite-0.1.0.jar"
TOOLS = sys.argv[2] if len(sys.argv) > 2 else "tools/sample-tools.yaml"

proc = subprocess.Popen(
    ["java", "-jar", JAR, "--tools", TOOLS],
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

send({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
    "name": "active_job_info", "arguments": {"limit": 3}}})
result = recv(3)["result"]
print("== tools/call active_job_info(limit=3) ==")
print("isError:", result.get("isError"))
payload = json.loads(result["content"][0]["text"])
print("success:", payload["success"], "| rows:", payload["metadata"]["rowCount"],
      "| executionTime(ms):", payload["metadata"]["executionTime"])
for row in payload["data"]:
    print("  job:", row.get("JOB_NAME"), "cpu:", row.get("CPU_TIME"))

send({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {
    "name": "list_user_libraries", "arguments": {"library_pattern": "QSYS2%"}}})
result = recv(4)["result"]
print("== tools/call list_user_libraries(library_pattern='QSYS2%') ==")
payload = json.loads(result["content"][0]["text"])
print("success:", payload["success"], "| rows:", payload["metadata"]["rowCount"])
for row in payload["data"][:5]:
    print("  ", row.get("LIBRARY"))

# error path: missing required parameter
send({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {
    "name": "list_user_libraries", "arguments": {}}})
result = recv(5)["result"]
print("== tools/call list_user_libraries(missing arg) ==")
print("isError:", result.get("isError"), "|", result["content"][0]["text"])

proc.terminate()
print("SMOKE TEST PASSED")
