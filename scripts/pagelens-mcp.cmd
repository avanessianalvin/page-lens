@echo off
rem PageLens MCP server launcher for Claude Desktop / Claude Code on Windows.
rem stdout is the MCP protocol channel: this script must never print anything to it.
rem Java: set PAGELENS_JAVA to a JDK 21+ java.exe, else JAVA_HOME is used (must also be 21+).
setlocal
set "ROOT=%~dp0.."
if not defined PAGELENS_ENV_FILE set "PAGELENS_ENV_FILE=%ROOT%\.env"
if not defined PAGELENS_LOG_DIR set "PAGELENS_LOG_DIR=%ROOT%\logs"
set "JAVA_EXE=%PAGELENS_JAVA%"
if not defined JAVA_EXE if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE set "JAVA_EXE=java"
"%JAVA_EXE%" -jar "%ROOT%\app-mcp\target\pagelens-mcp.jar" %*
