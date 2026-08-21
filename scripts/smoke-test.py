#!/usr/bin/env python3
"""Smoke test: drives the stdio MCP server with raw JSON-RPC.

Sends initialize -> notifications/initialized -> tools/list -> tools/call
and prints each response. Requires a .env (or real environment) with the
DB2i_* variables used by the tools YAML.

Usage:
  python3 scripts/smoke-test.py [path/to/server.jar] [path/to/tools.yaml]
                                [--builtin-tools] [--execute-sql]

With --builtin-tools, asserts discovery tool names in tools/list and calls
list_schemas (needs a working Mapepire connection).

With --execute-sql, also exercises execute_sql read-only rejection (no live DB
required) and attempts a SELECT (needs a working Mapepire connection).

describe_sql_object is always expected in tools/list when the YAML defines sources,
and is called on every run against QSYS2/SYSSCHEMAS.
"""
import json
import os
import subprocess
import sys
import threading

args = sys.argv[1:]
enable_builtin_tools = "--builtin-tools" in args
if enable_builtin_tools:
    args.remove("--builtin-tools")
enable_execute_sql = "--execute-sql" in args
if enable_execute_sql:
    args.remove("--execute-sql")

JAR = args[0] if len(args) > 0 else "target/ibmi-mcp-server-lite-0.1.0.jar"
TOOLS = args[1] if len(args) > 1 else "tools/sample/sample-tools.yaml"

DISCOVERY_TOOLS = (
    "list_schemas",
    "list_tables_in_schema",
    "get_table_columns",
    "get_related_objects",
    "validate_query",
)

server_cmd = ["java", "-jar", JAR, "--tools", TOOLS, "--transport", "stdio", "--no-reload"]
if enable_builtin_tools:
    server_cmd.append("--builtin-tools")
if enable_execute_sql:
    server_cmd.append("--execute-sql")

child_env = os.environ.copy()
# Make the smoke-test flags authoritative by overriding local .env toggles.
# Main.java resolves:
# - --builtin-tools vs IBMI_ENABLE_DEFAULT_TOOLS
# - --execute-sql vs IBMI_ENABLE_EXECUTE_SQL
# Real DB2i_* credentials still come from .env / environment.
child_env["IBMI_ENABLE_DEFAULT_TOOLS"] = "true" if enable_builtin_tools else "false"
child_env["IBMI_ENABLE_EXECUTE_SQL"] = "true" if enable_execute_sql else "false"

proc = subprocess.Popen(
    server_cmd,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    env=child_env)

# surface server logs (stderr) without blocking
threading.Thread(target=lambda: [print("[server]", l, end="", file=sys.stderr) for l in proc.stderr],
                 daemon=True).start()

_next_id = 1


def send(msg):
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()


def next_id():
    global _next_id
    rid = _next_id
    _next_id += 1
    return rid


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


init_id = next_id()
send({"jsonrpc": "2.0", "id": init_id, "method": "initialize", "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {"name": "smoke-test", "version": "0.0.1"}}})
init = recv(init_id)
print("== initialize ==")
print(json.dumps(init["result"]["serverInfo"], indent=2))

send({"jsonrpc": "2.0", "method": "notifications/initialized"})

list_id = next_id()
send({"jsonrpc": "2.0", "id": list_id, "method": "tools/list"})
tools = recv(list_id)["result"]["tools"]
print(f"== tools/list ({len(tools)} tools) ==")
for t in tools:
    print(f"  {t['name']}: required={t['inputSchema'].get('required', [])}")

tool_names = {t["name"] for t in tools}
if "describe_sql_object" not in tool_names:
    raise SystemExit("describe_sql_object not in tools/list (always-on builtin expected)")

if enable_builtin_tools:
    missing = [n for n in DISCOVERY_TOOLS if n not in tool_names]
    if missing:
        raise SystemExit(
            f"discovery tools missing from tools/list with --builtin-tools: {missing}")
elif any(n in tool_names for n in DISCOVERY_TOOLS):
    present = [n for n in DISCOVERY_TOOLS if n in tool_names]
    raise SystemExit(
        f"discovery tools present without --builtin-tools (unexpected): {present}")

if enable_execute_sql:
    if "execute_sql" not in tool_names:
        raise SystemExit("execute_sql not in tools/list (expected with --execute-sql)")
elif "execute_sql" in tool_names:
    raise SystemExit("execute_sql present without --execute-sql (unexpected)")

call_id = next_id()
send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
    "name": "fetch_all_libraries", "arguments": {}}})
print_tool_result("tools/call fetch_all_libraries()", recv(call_id)["result"])

call_id = next_id()
send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
    "name": "list_user_libraries", "arguments": {"library_pattern": "QSYS2%"}}})
print_tool_result("tools/call list_user_libraries(library_pattern='QSYS2%')", recv(call_id)["result"])

# error path: missing required parameter
call_id = next_id()
send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
    "name": "list_user_libraries", "arguments": {}}})
print_tool_result("tools/call list_user_libraries(missing arg)", recv(call_id)["result"])

# describe_sql_object is always on and is the only tool running a CALL that returns a
# result set, so exercise it on every run. QSYS2.SYSCOLUMNS2 generates well over 100 DDL
# lines, which is what makes this a regression test for the single-fetch row cap.
call_id = next_id()
send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
    "name": "describe_sql_object",
    "arguments": {
        "object_library": "QSYS2", "object_name": "SYSCOLUMNS2", "object_type": "VIEW"}}})
describe_result = recv(call_id)["result"]
print_tool_result("tools/call describe_sql_object(QSYS2/SYSCOLUMNS2 VIEW)", describe_result)
describe_payload = parse_tool_payload(describe_result)
if describe_result.get("isError") or describe_payload is None \
        or not describe_payload.get("success"):
    detail = (describe_payload or {}).get("error", "(no payload)")
    raise SystemExit(f"describe_sql_object should succeed against live Mapepire: {detail}")
describe_rows = describe_payload.get("data") or []
if not any("SRCDTA" in row for row in describe_rows):
    raise SystemExit(
        f"describe_sql_object returned no SRCDTA source lines: {describe_rows[:2]}")
if describe_payload.get("metadata", {}).get("truncated"):
    raise SystemExit("describe_sql_object DDL was truncated; fetchAllRows is not in effect")
if len(describe_rows) <= 100:
    raise SystemExit(
        f"describe_sql_object returned only {len(describe_rows)} DDL lines, so this check no "
        "longer proves the row cap is gone - pick an object with longer DDL")

# error path: GENERATE_SQL returns an empty result set for a missing object rather than
# raising, so this must come back as a failure and not as a success with no rows.
call_id = next_id()
send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
    "name": "describe_sql_object",
    "arguments": {
        "object_library": "QSYS2", "object_name": "NO_SUCH_OBJECT_XYZ",
        "object_type": "TABLE"}}})
missing_result = recv(call_id)["result"]
print_tool_result("tools/call describe_sql_object(missing object)", missing_result)
missing_payload = parse_tool_payload(missing_result)
if not missing_result.get("isError") or missing_payload is None \
        or missing_payload.get("success"):
    raise SystemExit("describe_sql_object should report a missing object as an error")
missing_error = str(missing_payload.get("error", ""))
if "NO_SUCH_OBJECT_XYZ" not in missing_error or "QSYS2" not in missing_error:
    raise SystemExit(
        f"missing-object error should name the object and library searched: {missing_error}")

if enable_builtin_tools:
    call_id = next_id()
    send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
        "name": "list_schemas",
        "arguments": {"filter": "*ALL", "include_system": False, "limit": 5, "offset": 0}}})
    list_schemas_result = recv(call_id)["result"]
    print_tool_result("tools/call list_schemas(limit=5)", list_schemas_result)
    payload = parse_tool_payload(list_schemas_result)
    if list_schemas_result.get("isError") or payload is None or not payload.get("success"):
        raise SystemExit("list_schemas should succeed against live Mapepire with --builtin-tools")

if enable_execute_sql:
    # Read-only rejection is validated before any DB connection.
    call_id = next_id()
    send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
        "name": "execute_sql",
        "arguments": {"sql": "DELETE FROM SAMPLE.EMPLOYEE"}}})
    delete_result = recv(call_id)["result"]
    print_tool_result("tools/call execute_sql(DELETE ...)", delete_result)
    delete_payload = parse_tool_payload(delete_result)
    if not delete_result.get("isError") or delete_payload is None or delete_payload.get("success"):
        raise SystemExit("execute_sql DELETE should be rejected in read-only mode")
    error = str(delete_payload.get("error", ""))
    if "read-only" not in error.lower() and "delete" not in error.lower():
        raise SystemExit(f"unexpected execute_sql DELETE error: {error}")

    call_id = next_id()
    send({"jsonrpc": "2.0", "id": call_id, "method": "tools/call", "params": {
        "name": "execute_sql",
        "arguments": {"sql": "SELECT 1 AS ONE FROM SYSIBM.SYSDUMMY1"}}})
    print_tool_result("tools/call execute_sql(SELECT 1 ...)", recv(call_id)["result"])

proc.terminate()
print("SMOKE TEST PASSED")
