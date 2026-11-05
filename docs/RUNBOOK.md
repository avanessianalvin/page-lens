# PageLens Runbook

First-time install: [SETUP.md](SETUP.md). All commands run from the repo root with `JAVA_HOME` pointing to JDK 21.

## Build and test

```
./mvnw verify                                  # full build + all tests
./mvnw -pl core test                           # one module
./mvnw -pl core test -Dtest=FlywayMigrationTest                       # one test class
./mvnw -pl core test -Dtest=FlywayMigrationTest#baselineCreatesAllCoreTables   # one test method
./mvnw -pl app-web -am test -Dtest=PageLensWebApplicationTest -Dsurefire.failIfNoSpecifiedTests=false  # app test, rebuilding core first
```

Integration tests start a throwaway `postgres:17-alpine` container. When Docker isn't running they are **skipped** (reported as "Skipped" in the summary), so check the skip count before trusting a green build.

## Start and stop

| What | Start | Stop |
|---|---|---|
| Dashboard / REST API | `java -jar app-web/target/pagelens-web.jar` or `./mvnw -pl app-web -am spring-boot:run` | Ctrl+C |
| MCP server | Launched by Claude (config arrives in Phase 2). Phase 0: `java -jar app-mcp/target/pagelens-mcp.jar` boots, checks the DB and exits. | n/a |

The dashboard listens on `http://127.0.0.1:8080` only.

Health: `curl http://127.0.0.1:8080/actuator/health`

## Logs

All under `PAGELENS_LOG_DIR` (default `./logs`). JSON lines, rotated daily or at 20 MB, gzip-compressed.

| File | Content | Kept |
|---|---|---|
| `app.log` | everything | 14 days |
| `api-calls.log` | one line per Instagram API call (logger `pagelens.api-calls`) | 30 days |
| `audit.log` | every write action (logger `pagelens.audit`) | 90 days |

Every line from one request, sync run or MCP call shares a `correlationId`. HTTP responses return it in the `X-Correlation-Id` header.

Change a log level at runtime (web app):

```
curl -X POST http://127.0.0.1:8080/actuator/loggers/com.pagelens -H "Content-Type: application/json" -d '{"configuredLevel":"DEBUG"}'
```

The MCP process logs to **stderr and the log files only**. stdout is the MCP protocol channel.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Build fails with "PageLens needs JDK 21+" | Set `JAVA_HOME` to JDK 21 (see SETUP.md). |
| `Connection to localhost:5432 refused` | Start PostgreSQL (Windows: Services → `postgresql-x64-17` → Start). |
| `password authentication failed for user "pagelens"` | `.env` password doesn't match the DB role. Fix with `ALTER ROLE pagelens PASSWORD '...'`. |
| `permission denied for schema pagelens` / `schema "pagelens" does not exist` | Run `scripts/db/create-schema.sql` as `postgres` (SETUP.md §2). |
| Flyway `Validate failed: Migrations have failed validation` | A migration that already ran was edited. Never edit an applied migration; add a new `V<n>__*.sql`. |
| Integration tests show as skipped | Start Docker Desktop. |

## Backup / restore

Arrives in Phase 4 (nightly `pg_dump` script). Until then, dump only the PageLens schema: `pg_dump -h localhost -U pagelens -d postgres -n pagelens -Fc > pagelens.dump`. Restore with `pg_restore -h localhost -U pagelens -d postgres --clean pagelens.dump`. Back up `PAGELENS_ENCRYPTION_KEY` separately, because without it the stored tokens are unusable.
