#!/QOpenSys/usr/bin/sh
#
# Launcher installed as /QOpenSys/pkgs/bin/ibmi-mcp-server-lite.
# The QIBM_* variables are the standard PASE/Java stdio-descriptor settings
# required for well-behaved stdin/stdout on IBM i (same set mapepire uses) —
# essential here because MCP stdio framing runs over stdin/stdout.
#
# TODO: point JAVA at a Java 17 runtime once one is available on the
# system (see docs/running-on-ibmi.md).
JAVA=${JAVA:-/QOpenSys/pkgs/lib/jvm/openjdk-11/bin/java}

QIBM_JAVA_STDIO_CONVERT=N \
QIBM_PASE_DESCRIPTOR_STDIO=B \
QIBM_USE_DESCRIPTOR_STDIO=Y \
QIBM_MULTI_THREADED=Y \
exec "$JAVA" -jar /QOpenSys/pkgs/lib/ibmi-mcp-server-lite/ibmi-mcp-server-lite.jar \
  --tools /QOpenSys/etc/ibmi-mcp-server-lite/tools.yaml "$@"
