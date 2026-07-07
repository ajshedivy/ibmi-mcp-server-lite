#!/QOpenSys/usr/bin/sh
#
# Launcher installed as /QOpenSys/pkgs/bin/ibmi-mcp-server-lite.
# The QIBM_* variables are the standard PASE/Java stdio-descriptor settings
# required for well-behaved stdin/stdout on IBM i (same set mapepire uses) —
# essential here because MCP stdio framing runs over stdin/stdout.
#
# Uses IBM Technology for Java 17 (Semeru Certified Edition, 5770-JV1 option 20),
# the supported Java 17 runtime on IBM i (see docs/running-on-ibmi.md). Override
# JAVA to point at a different Java 17+ install if needed.
JAVA=${JAVA:-/QOpenSys/QIBM/ProdData/JavaVM/jdk17/64bit/bin/java}

QIBM_JAVA_STDIO_CONVERT=N \
QIBM_PASE_DESCRIPTOR_STDIO=B \
QIBM_USE_DESCRIPTOR_STDIO=Y \
QIBM_MULTI_THREADED=Y \
exec "$JAVA" -jar /QOpenSys/pkgs/lib/ibmi-mcp-server-lite/ibmi-mcp-server-lite.jar \
  --tools /QOpenSys/etc/ibmi-mcp-server-lite/tools.yaml "$@"
