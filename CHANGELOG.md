# Changelog

## [Unreleased]

### Phase 0: Foundation

- Maven multi-module build (`core`, `app-web`, `app-mcp`) on Spring Boot 4.1.2 / Java 21, with the Maven wrapper and an enforcer check for JDK 21+.
- Flyway baseline schema `V1` with every table from plan section 6, plus `ig_account.login_type`/`facebook_page_id` so accounts can use either Instagram Login or Facebook Login.
- PageLens uses a dedicated `pagelens` schema in the existing `postgres` database rather than its own database (`scripts/db/create-schema.sql`). Flyway, Hibernate and the connection pool are all pinned to that schema.
- Shared configuration in core's `config/application.yml` (auto-loaded by Spring Boot), loading `.env` and env vars. Dashboard bound to `127.0.0.1`.
- Security overrides of Boot-managed versions: jackson-databind 3.1.8, logback 1.6.6 (runtime); json-smart 2.6.1, asm 9.11.1, and org.json in place of android-json (test only).
- Structured JSON logging to `app.log`, `api-calls.log`, `audit.log` with rotation. Correlation ids per HTTP request (`X-Correlation-Id`).
- MCP process logs only to stderr and files; a test guards that stdout stays empty.
- Actuator health and runtime log levels.
- Docs: SETUP (incl. Meta app walkthrough), API_NOTES (verified 2026-11-05), RUNBOOK.

### Decisions

- Spring Boot 4.1.x instead of 3.x (3.x is out of OSS support). Java 21 for now; Java 25 is a later one-line change.
- Support both Meta login setups per account (owner's accounts are mixed).
- Dashboard strictly local. Write actions stay in Phase 3, disabled by default. Analysis time zone is the system default, configurable.
