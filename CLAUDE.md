# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

PageLens is a local-first Instagram Insights service (Instagram Graph API → PostgreSQL → MCP server + REST API + Vue dashboard). The full spec is `docs/Instagram Insights MCP Service Project Plan.md`. It is the source of truth for schema, REST endpoints, MCP tool names, and dashboard pages; read it before starting a phase. **Phases 0, 1 and 2 are done** (Phase 3, write actions, is next); see `CHANGELOG.md` for what exists and which decisions the owner has made. The owner makes all git commits: never commit.

## Commands

The build needs **JDK 21+**, but this machine's default `JAVA_HOME` is JDK 17. Set `JAVA_HOME` before running Maven (Git Bash: `export JAVA_HOME="/c/Program Files/Java/jdk-21"`). The enforcer plugin fails fast otherwise. Always use the wrapper.

```
./mvnw verify                                        # build + all tests
./mvnw -pl core test -Dtest=SyncServiceTest          # one class (append #method for one method)
./mvnw -pl app-web -am test -Dtest=PageLensWebApplicationTest -Dsurefire.failIfNoSpecifiedTests=false
java -jar app-web/target/pagelens-web.jar            # dashboard + API on http://127.0.0.1:8080

cd frontend && npm run dev                           # Vite on :5173, proxies /api, /auth, /actuator to :8080
cd frontend && npm run build                         # type-check, then build into app-web/src/main/resources/static
cd frontend && npm run gen:api                       # regenerate src/api/schema.d.ts from app-web/target/openapi.json
```

`app-web/target/openapi.json` is produced by the test `PageLensWebApplicationTest#exportsOpenApiSpec`. After changing a controller or DTO: run the app-web tests, then `npm run gen:api`, then `npm run build`. The type-check flags any UI code that no longer matches. `gen:api` runs openapi-typescript through `npx` with its own TypeScript 5, because it doesn't support the project's TS 6 yet.

Integration tests use Testcontainers (`postgres:17-alpine`) plus `FakeGraphApi` (a JDK HttpServer serving JSON fixtures from `core/src/test/resources/fixtures/graph`). They are **skipped, not failed, when Docker is down**, so check the skip count. Shared test setup (`com.pagelens.testsupport`: `PostgresTestcontainer`, `FakeGraphApi`, `PageLensTestProperties`) and the fixtures are exported to the app modules through core's test-jar.

More operational detail is in `docs/RUNBOOK.md`. Install, Meta app setup and dashboard login are in `docs/SETUP.md`.

## Working rules from the plan

- Build phase by phase (0: foundation, 1: read-only core, 2: MCP + analysis, 3: write actions, 4: hardening). Stop after each phase to summarize what exists.
- Before writing any Instagram API call, check Meta's current docs and record findings with a date in `docs/API_NOTES.md`. That file also lists facts still awaiting confirmation on a live account. Meta's machine-readable index (`developers.facebook.com/documentation/instagram-platform/llms.txt`) links the `.md` reference pages.
- Ask the owner before any scope-changing decision (switching database, building write features early, etc.).
- Keep `CHANGELOG.md` and `docs/RUNBOOK.md` up to date.
- Write tests alongside each service, using fixtures (`FakeGraphApi`).

## Architecture

Spring Boot 4.1 (Jackson 3 = `tools.jackson.*`, modular starters such as `spring-boot-starter-webmvc`), Java 21, PostgreSQL, Flyway. Maven multi-module, groupId `com.pagelens`:

- `core`: everything domain-side, with **no Spring Web or MCP dependencies**.
  - `instagram`: transport (`GraphApiClient`, JDK HttpClient) → typed reads (`InstagramApi`) → OAuth (`MetaOAuthClient`).
  - `auth`: `TokenService` (encrypt, refresh, expiry check) and `AccountConnectionService`.
  - `sync`: `SyncService` and `SyncScheduler`.
  - `repo`: `JdbcClient` repositories with explicit SQL, returning records. There is **no JPA**.
  - `crypto`: `TokenCipher`.
  - `analysis`: `AnalysisService` loads local data, and `Analytics` holds the pure aggregation rules. Add new analysis rules there and unit-test them without a database. Shared by REST and MCP.
- `app-web`: REST API (`com.pagelens.web.api`, under `/api`), Spring Security, the OAuth callback (`/auth/callback`), `SpaController` serving `static/index.html` for client routes, and `@EnableScheduling`. **Only app-web runs scheduled syncs.**
- `app-mcp`: stdio MCP server named `pagelens` (official MCP Java SDK 2.1.0, Jackson 3 binding).
  - `PageLensTools` defines all tools: JSON-Schema inputs via `ToolArgs.Schema`, compact output via `McpViews` (no nulls, owner-zone times).
  - `PageLensResources` holds the glossary resource (`src/main/resources/mcp/metric-glossary.md`) and prompts.
  - `PageLensMcpServer` builds the server on any streams; tests use pipes.
  - `StdioRunner` serves the real fd 0/1 and blocks until the client disconnects.
  - Tool descriptions are what Claude uses to choose a tool, so keep them precise.
  - On stdin EOF the server holds EOF back from the SDK transport until in-flight calls have answered (`EofSignallingInputStream`). The SDK closes its output the moment it reads EOF.
- `frontend/`: Vue 3 + Vite + TS + Pinia + PrimeVue **4.5.5** + ECharts. `src/api/client.ts` wraps openapi-fetch and adds the XSRF header. Pages live in `src/views`. Charts use `src/components/charts/` (`LineChart`, `BarChart`, `HeatmapChart`, tokens in `theme.ts`), following the dataviz rules: one series and one axis per chart, a single hue (sequential blue for heatmaps), small samples in muted gray *and* labelled, a hover tooltip, and a table toggle.

Both app main classes live in package `com.pagelens`, so scanning picks up `com.pagelens.core.*`. Don't define a `JsonMapper` bean in core, because Boot would back off its own web mapper. Core uses the `GraphJson` wrapper instead.

Configuration: shared settings live in `core/src/main/resources/config/application.yml`. Boot loads that location automatically, with higher precedence than each app's `application.yml`, so don't redefine its keys in app yml files and never add `config/application.yml` to an app module. It imports `instagram-metrics.yml`, `.env` (as properties; `PAGELENS_ENV_FILE`) and `./application-local.yml`. Property groups: `PageLensProperties` (`pagelens.*`), `MetaProperties`, `HttpProperties`, `SyncProperties`, `MetricCatalog`.

**Metric names live only in `core/src/main/resources/instagram-metrics.yml`** (per media product type, plus Facebook-Login-only extras). `SyncService` maps known metrics to snapshot columns. Everything else goes to `extra_metrics` JSON, and metrics the API rejected go to `extra_metrics._unsupported`.

Sync flow: `SyncService.claim` locks the `ig_account` row (`FOR UPDATE`) and refuses to start if another run is RUNNING, in any process. Then the steps run: profile, media, insights, comments, daily, audience. A step's failure is recorded, and the run ends PARTIAL. `TOKEN_INVALID` marks the account NEEDS_RECONNECT and aborts. `RATE_LIMITED` aborts.

Rate limiting is shared across processes: `GraphApiClient` records Meta's usage headers in `api_call_log` (`usage_percent`, `regain_access_at`), and `RateLimitGuard` reads the latest values (cached for 15 s) before every call.

Dependency versions: the parent `pom.xml` overrides several Boot-managed versions for security fixes (jackson, logback 1.6, json-smart, asm; android-json is swapped for org.json). Drop each override once a Boot release manages an equal or newer version.

Logging: both apps include `core/src/main/resources/logback-pagelens-files.xml`, which writes JSON to `app.log`, `api-calls.log` (logger `pagelens.api-calls`) and `audit.log` (logger `pagelens.audit`, written via `AuditLogRepository.record`). Use `CorrelationId.open(prefix)` to scope sync runs and MCP calls. HTTP requests get one from `CorrelationIdFilter`.

## Critical constraints

- **Never write to stdout in `app-mcp`** except MCP frames: no banner, no `System.out`, no console appender on stdout. `PageLensMcpApplicationTest` asserts startup stdout stays empty, and `PageLensMcpProtocolTest` asserts every output line is JSON-RPC; keep both passing. The launch scripts must not echo anything either.
- MCP read tools must never call Instagram. Only `sync_now` may, and it's audited. Tool output goes into Claude's context, so keep it compact, and use `limit` parameters for anything list-shaped.
- **Never log or return tokens or secrets.** Route every URL and message through `SecretMasker` before logging or storing it. Records holding tokens (`AccountRef`, `IssuedToken`, `StoredToken`, `TokenConnectRequest`) override `toString()` to exclude them. Keep comment text out of logs.
- Accounts use either Instagram Login (`graph.instagram.com`, `/me` returns `user_id` = the IG account id) or Facebook Login (`graph.facebook.com`, a linked Page). Fields and metrics differ between the two (see API_NOTES), so requests degrade gracefully instead of failing.
- Tokens are stored AES-256-GCM encrypted, with the account id as associated data. The key is `PAGELENS_ENCRYPTION_KEY`. Never commit `.env` or `application-local.yml`.
- Write actions are Phase 3, off by default (`pagelens.write-actions-enabled`), support `dry_run`, and are audited. Publishing must be idempotent.
- The dashboard binds to `127.0.0.1` only (owner decision). Security: form login with a bcrypt hash (`PAGELENS_WEB_PASSWORD_HASH`), SPA cookie CSRF (`X-XSRF-TOKEN`), `/api/**` → 401, login lockout. Note that Spring Security's MockMvc support replaces the CSRF repository, so cookie behavior can only be verified against the running app.
- Database: PageLens shares the `postgres` database and owns only the `pagelens` schema (`PAGELENS_DB_SCHEMA`). Keep SQL unqualified and never create objects in `public`. `FlywayMigrationTest` asserts that. Bind `TIMESTAMPTZ` values as UTC `OffsetDateTime` (`Db.ts`).
- Never edit an applied Flyway migration; add a new `V<n>__*.sql`. SQL stays portable where practical (JSON stored as TEXT).
- Metric values are nullable: NULL means "not returned by the API", never zero. Upserts use `COALESCE(EXCLUDED.x, old.x)` so a missing value doesn't overwrite a known one. The UI shows "—".
- Timestamps are stored in UTC. Display and analysis use `PageLensProperties.zoneId()`. Account-insight days are UTC days.
- Captions and comments can be Farsi or Armenian: render user text with `dir="auto"` and the `pl-text` class.
- Frontend UI library: stay on MIT-licensed PrimeVue 4.x, primeicons 7 and `@primeuix/themes` 2. Their newer majors need a PrimeUI license key, which is the owner's decision.
