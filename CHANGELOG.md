# Changelog

## [Unreleased]

### Phase 1: Read-only core

- **Meta API research** recorded in `docs/API_NOTES.md` before any client code: Graph API pinned to v27.0, verified endpoints, fields and metrics per media type, deprecations (`impressions`, `follower_count`/`profile_views` no longer documented), rate-limit headers and codes, and a checklist of facts that need a live account to confirm.
- **Instagram client** (`core`, JDK HttpClient + Jackson, no web dependencies): both Instagram Login and Facebook Login. Retries transient errors with exponential backoff and classifies errors (token invalid, permission, rate-limited, invalid request, not enough data). If Meta rejects a field, the request is retried with a minimal field set. If it rejects a metric, the metrics are retried one by one and the rejected ones are reported, never stored as 0.
- **Shared rate limiting:** usage headers are recorded in `api_call_log`. All PageLens processes read them before calling Meta, slow down at 75 % and stop at 95 % or while Meta says access is blocked.
- **Secret masking:** tokens, secrets and OAuth codes are masked in every log line, `api_call_log` row and error message. Tested.
- **OAuth** for both login setups (state-checked callback at `/auth/callback`, long-lived token exchange), plus a paste-a-token fallback for dashboard-generated tokens. Facebook Login connects every IG account linked to the user's Pages.
- **Tokens** are encrypted with AES-256-GCM and bound to their account. A daily check refreshes Instagram Login tokens, warns 7 days before expiry, and marks expired or rejected tokens so the account shows *Needs reconnect*.
- **Sync engine:** idempotent upserts. Covers profile and follower snapshot, posts with paging, insight snapshots (recent posts every run, older ones in batches), comments with replies, daily account metrics (derived follower delta, `follows_and_unfollows`), audience demographics once a day, stories every 3 hours, and backfill. One run per account across processes. Runs abandoned by a crashed process are cleaned up. Scheduled only in app-web.
- **Disconnect purges all account data** (Meta policy) and is audited.
- **Migration V2:** sync columns, shared rate-limit state, audience `metric` column.
- **REST API** under `/api`: accounts, connect, disconnect, sync now, posts (filter, sort, page), post detail with snapshot history, comments, daily insights, audience (small-sample flag under 30), sync runs, rate limit, settings, and logs (API calls, audit, app.log tail). OpenAPI via springdoc 3.2.0.
- **Dashboard security:** single-user form login with a bcrypt hash (`--hash-password` helper), SPA cookie CSRF, 401 for `/api` instead of redirects, a 15-minute lockout after 5 failed logins, health details only when logged in.
- **Vue dashboard** (Vue 3, Vite, TypeScript, Pinia, PrimeVue 4.5.5, ECharts) with a typed client generated from the OpenAPI spec. Pages: Login, Accounts (connect, paste token, token expiry warning, sync now, disconnect), Posts (filters, metric sort, thumbnails), Post detail (latest metrics, snapshot chart with a table view, comment threads), Logs (app/API/audit/sync tabs, correlation-id drill-down, live refresh). Farsi and Armenian text uses `dir="auto"`, times show in the owner's time zone, and missing values show as "—".
- **Tests:** 53, none skipped. They cover unit tests with a fake Graph API and JSON fixtures, the end-to-end sync against PostgreSQL (Testcontainers), and web security and API tests.

### Phase 1 decisions

- **Unified Login not implemented.** It needs a business portfolio and system-user tokens, and its docs are incomplete. Instagram Login and Facebook Login cover the owner's mixed accounts.
- **Persistence uses `JdbcClient` with explicit SQL instead of JPA entities.** Sync is upsert-heavy (`ON CONFLICT`), reads are analytic, and plain SQL keeps the SQLite option visible. Hibernate was removed.
- **PrimeVue 4.5.5 (MIT) instead of 5.x.** PrimeVue 5, primeicons 8 and `@primeuix/themes` 3 switched to the PrimeUI license, which needs a registered key even for the free Community tier.
- **Account-insight days are UTC days**, because Meta's day boundaries are undocumented. Display uses the owner's zone.

### Phase 0: Foundation

- Maven multi-module build (`core`, `app-web`, `app-mcp`) on Spring Boot 4.1.2 / Java 21, with the Maven wrapper and an enforcer check for JDK 21+.
- Flyway baseline schema `V1` with every table from plan section 6, plus `ig_account.login_type`/`facebook_page_id` so accounts can use either Instagram Login or Facebook Login.
- PageLens uses a dedicated `pagelens` schema in the existing `postgres` database rather than its own database (`scripts/db/create-schema.sql`). Flyway and the connection pool are pinned to that schema.
- Shared configuration in core's `config/application.yml` (auto-loaded by Spring Boot), loading `.env` and env vars. Dashboard bound to `127.0.0.1`.
- Security overrides of Boot-managed versions: jackson-databind 3.1.8, logback 1.6.6 (runtime); json-smart 2.6.1, asm 9.11.1, and org.json in place of android-json (test only).
- Structured JSON logging to `app.log`, `api-calls.log`, `audit.log` with rotation. Correlation ids per HTTP request (`X-Correlation-Id`).
- MCP process logs only to stderr and files; a test guards that stdout stays empty.
- Actuator health and runtime log levels.
- Docs: SETUP (incl. Meta app walkthrough), API_NOTES (verified 2026-11-05), RUNBOOK.

### Phase 0 decisions

- Spring Boot 4.1.x instead of 3.x (3.x is out of OSS support). Java 21 for now; Java 25 is a later one-line change.
- Support both Meta login setups per account (owner's accounts are mixed).
- Dashboard strictly local. Write actions stay in Phase 3, disabled by default. Analysis time zone is the system default, configurable.
