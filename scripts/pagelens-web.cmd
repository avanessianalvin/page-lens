@echo off
rem Starts the PageLens dashboard, REST API and scheduler (app-web) on http://127.0.0.1:8080.
rem Java: set PAGELENS_JAVA to a JDK 21+ java.exe, else JAVA_HOME is used (must also be 21+).
setlocal
set "ROOT=%~dp0.."
if not defined PAGELENS_ENV_FILE set "PAGELENS_ENV_FILE=%ROOT%\.env"
if not defined PAGELENS_LOG_DIR set "PAGELENS_LOG_DIR=%ROOT%\logs"
if not defined PAGELENS_BACKUP_DIR set "PAGELENS_BACKUP_DIR=%ROOT%\backups"
if not defined PAGELENS_UPLOAD_DIR set "PAGELENS_UPLOAD_DIR=%ROOT%\data\uploads"
set "JAVA_EXE=%PAGELENS_JAVA%"
if not defined JAVA_EXE if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE set "JAVA_EXE=java"
if not exist "%ROOT%\app-web\target\pagelens-web.jar" (
  echo pagelens-web.jar not found. Build first:  cd frontend ^&^& npm run build ^&^& cd .. ^&^& mvnw.cmd -DskipTests package
  exit /b 1
)
cd /d "%ROOT%"
"%JAVA_EXE%" -jar "%ROOT%\app-web\target\pagelens-web.jar" %*
