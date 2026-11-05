#!/usr/bin/env sh
# PageLens MCP server launcher for Claude Desktop / Claude Code (macOS, Linux, Git Bash).
# stdout is the MCP protocol channel: this script must never print anything to it.
# Java: set PAGELENS_JAVA to a JDK 21+ java binary, else JAVA_HOME is used (must also be 21+).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${PAGELENS_ENV_FILE:=$ROOT/.env}"
: "${PAGELENS_LOG_DIR:=$ROOT/logs}"
export PAGELENS_ENV_FILE PAGELENS_LOG_DIR
JAVA_EXE="${PAGELENS_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
exec "${JAVA_EXE:-java}" -jar "$ROOT/app-mcp/target/pagelens-mcp.jar" "$@"
