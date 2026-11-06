#!/usr/bin/env sh
# Starts the PageLens dashboard, REST API and scheduler (app-web) on http://127.0.0.1:8080.
# Java: set PAGELENS_JAVA to a JDK 21+ java binary, else JAVA_HOME is used (must also be 21+).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${PAGELENS_ENV_FILE:=$ROOT/.env}"
: "${PAGELENS_LOG_DIR:=$ROOT/logs}"
: "${PAGELENS_BACKUP_DIR:=$ROOT/backups}"
: "${PAGELENS_UPLOAD_DIR:=$ROOT/data/uploads}"
export PAGELENS_ENV_FILE PAGELENS_LOG_DIR PAGELENS_BACKUP_DIR PAGELENS_UPLOAD_DIR
JAR="$ROOT/app-web/target/pagelens-web.jar"
if [ ! -f "$JAR" ]; then
  echo "pagelens-web.jar not found. Build first: (cd frontend && npm run build) && ./mvnw -DskipTests package" >&2
  exit 1
fi
JAVA_EXE="${PAGELENS_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
cd "$ROOT" && exec "${JAVA_EXE:-java}" -jar "$JAR" "$@"
