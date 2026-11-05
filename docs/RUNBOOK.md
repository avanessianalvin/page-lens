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

Integration tests start a throwaway `postgres:17-alpine` container and a fake Graph API (`FakeGraphApi`, JSON fixtures in `core/src/test/resources/fixtures/graph`). When Docker isn't running they are **skipped** (reported as "Skipped" in the summary), so check the skip count before trusting a green build.

## Frontend

```
cd frontend
npm run dev        # http://localhost:5173 with hot reload; proxies /api, /auth, /actuator to :8080
npm run build      # type-check + build into app-web/src/main/resources/static (git-ignored)
npm run gen:api    # regenerate src/api/schema.d.ts from app-web/target/openapi.json
```

`app-web/target/openapi.json` is written by `PageLensWebApplicationTest#exportsOpenApiSpec`, so after changing a controller or DTO run `./mvnw -pl app-web -am test` and then `npm run gen:api`. The type-check step catches any frontend code that no longer matches. In dev mode the Spring app must be running too. Log in via the Vite URL: the session cookie is proxied.

## Start and stop

| What | Start | Stop |
|---|---|---|
| Dashboard / REST API / scheduler | `java -jar app-web/target/pagelens-web.jar` (rebuild the frontend first if it changed) | Ctrl+C |
| MCP server | Launched by Claude (SETUP.md §8). It runs while the Claude session is open. | Claude closes stdin; the server finishes in-flight calls (max 15 s) and exits |

The dashboard listens on `http://127.0.0.1:8080` only. Only app-web runs scheduled syncs. A sync left `RUNNING` by a crashed process is marked `FAILED` after 2 hours.

- Health: `curl http://127.0.0.1:8080/actuator/health`
- API docs (logged in): `http://127.0.0.1:8080/swagger-ui.html`

## Syncing

- **Sync now**: Accounts page, or `POST /api/accounts/{id}/sync?job=FULL|BACKFILL|STORIES`. One run per account at a time, across processes (409 if one is running).
- **FULL** (scheduled, `PAGELENS_SYNC_CRON`): profile + follower snapshot, new posts, insights for posts < 7 days (and up to 25 stale older posts), comments for posts < 14 days, the last 3 days of account metrics, audience once a day.
- **BACKFILL**: every post Meta returns (max 10K), insights for up to 500 posts without any, 90 days of account metrics. A new account's first FULL sync behaves like this automatically.
- **STORIES** (`PAGELENS_STORIES_CRON`, every 3 h): live stories and their insights. Shows `SKIPPED` if Meta refuses the stories edge for the account (expected for some Instagram Login accounts).
- Run outcomes: `SUCCEEDED`, `PARTIAL` (some steps failed; see the error text), `FAILED` (token invalid, rate-limited, or crashed), `SKIPPED`.

## MCP server

- **Tools:** 13 read tools plus `sync_now`. Read tools answer from the local database only and never call Instagram. `sync_now` starts a background sync inside the MCP process (`triggered_by = mcp`, audited as `sync.start`). If Claude closes the session mid-sync, the run is abandoned and later marked `FAILED`.
- **Resources and prompt:** resources `pagelens://glossary/metrics` and `pagelens://accounts`; prompt `weekly_performance_review`.
- **Logs:** every tool call is logged as `MCP tool <name> ok in N ms` with its own `mcp-…` correlation id, in the shared `app.log` and on stderr. Claude Desktop also keeps the server's stderr in its own MCP log (Settings → Developer).
- **Manual check** without Claude: pipe JSON-RPC lines into the launcher and read stdout. Every stdout line must be a JSON-RPC message:
  ```
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_accounts","arguments":{}}}' \
    | PAGELENS_JAVA="/c/Program Files/Java/jdk-21/bin/java" sh scripts/pagelens-mcp.sh
  ```
- Bad tool input (an unknown metric or account) comes back as a tool error listing the valid values, so Claude can correct itself.

## Logs

All under `PAGELENS_LOG_DIR` (default `./logs`). JSON lines, rotated daily or at 20 MB, gzip-compressed.

| File | Content | Kept |
|---|---|---|
| `app.log` | everything | 14 days |
| `api-calls.log` | one line per Instagram API call (logger `pagelens.api-calls`) | 30 days |
| `audit.log` | every state-changing action, e.g. connect and disconnect (logger `pagelens.audit`) | 90 days |

The same information is in the database (`api_call_log`, `audit_log`, `sync_run`) and on the dashboard's **Logs** page. Click a correlation id there to see every line from that request or sync run. Access tokens and secrets are masked (`access_token=***`) before anything is logged or stored. Comment text is never logged.

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
| app-web won't start: `PAGELENS_WEB_PASSWORD_HASH is missing` | Generate it with `--hash-password` (SETUP.md §5). |
| `PAGELENS_ENCRYPTION_KEY is not set` when connecting | Add the key to `.env` (SETUP.md §4 step 10) and restart. |
| `Stored token could not be decrypted` | The encryption key changed. Restore the old key, or reconnect every account. |
| Account shows **Needs reconnect** | Meta rejected the token (expired or revoked). Reconnect from Accounts. Synced data is kept. |
| OAuth returns `redirect_uri` / URL-blocked error | The redirect URI isn't registered exactly as `PAGELENS_OAUTH_REDIRECT_URI`, or Meta refuses `http://localhost`. Use **Paste a token** instead. |
| Sync `FAILED: rate limited` | Meta's budget is used up. Calls pause until the time Meta gives (see Logs → API calls). Don't retry repeatedly: failed calls count too. |
| Metrics show `—` | Instagram didn't return them (hidden counts, under 100 followers, a metric that isn't supported for the format, or a story with fewer than 5 viewers). PageLens never stores these as 0. |
| Dashboard shows "has not been built" | `cd frontend && npm run build`, then repackage. |
| Claude shows the pagelens server as failed | Usually the wrong Java (it must be JDK 21; give the full path) or a missing `PAGELENS_ENV_FILE` (database login fails). Check the server's stderr in Claude's MCP log, or `logs/app.log`. |
| Claude says the data is stale | Is app-web running? Only app-web runs scheduled syncs. Use `get_sync_status`, or ask Claude to call `sync_now`. |
| Analysis says "smallSample" everywhere | Too few posts in the period. Widen it (`period=all`), or wait for more history. |

## Backup / restore

Arrives in Phase 4 (nightly `pg_dump` script). Until then, dump only the PageLens schema: `pg_dump -h localhost -U pagelens -d postgres -n pagelens -Fc > pagelens.dump`. Restore with `pg_restore -h localhost -U pagelens -d postgres --clean pagelens.dump`. Back up `PAGELENS_ENCRYPTION_KEY` separately, because without it the stored tokens are unusable.
