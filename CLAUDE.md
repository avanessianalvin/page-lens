# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

PageLens is a local-first Instagram Insights service (Instagram Graph API → PostgreSQL → MCP server + REST API + Vue dashboard). The full spec is `docs/Instagram Insights MCP Service Project Plan.md`. It is the source of truth for schema, REST endpoints, MCP tool names, and dashboard pages; read it before starting a phase. **Phase 0 (foundation) is done**; see `CHANGELOG.md` for what exists and which decisions the owner has made.

## Commands

The build needs **JDK 21+**, but this machine's default `JAVA_HOME` is JDK 17. Set `JAVA_HOME` before running Maven (Git Bash: `export JAVA_HOME="/c/Program Files/Java/jdk-21"`). The enforcer plugin fails fast otherwise. Always use the wrapper.

```
./mvnw verify                                        # build + all tests
./mvnw -pl core test -Dtest=FlywayMigrationTest      # one class (append #method for one method)
./mvnw -pl app-web -am test -Dtest=PageLensWebApplicationTest -Dsurefire.failIfNoSpecifiedTests=false
java -jar app-web/target/pagelens-web.jar            # dashboard API on http://127.0.0.1:8080
```

Integration tests use Testcontainers (`postgres:17-alpine`) and are **skipped, not failed, when Docker is down**. Check the skip count before trusting a green build. Shared test setup is `core/src/test/java/com/pagelens/testsupport/` (exported to the app modules via core's test-jar; only that package is exported).

More operational detail (logs, runtime log levels, troubleshooting) is in `docs/RUNBOOK.md`. Install and Meta app setup are in `docs/SETUP.md`.

## Working rules from the plan

- Build phase by phase (0: foundation, 1: read-only core, 2: MCP + analysis, 3: write actions, 4: hardening). Stop after each phase to summarize what exists.
- Before writing any Instagram API call, check Meta's current docs for the endpoint, permission, and metric names, and record findings with a date in `docs/API_NOTES.md`. That file has a "Still to verify" checklist for Phase 1.
- Ask the owner before any scope-changing decision (switching database, building write features early, etc.).
- Keep `CHANGELOG.md` and `docs/RUNBOOK.md` up to date.
- Write tests alongside each service, using recorded API fixtures.

## Architecture

Spring Boot 4.1 (Jackson 3, modular starters such as `spring-boot-starter-webmvc` and `spring-boot-starter-flyway`), Java 21, PostgreSQL, Flyway. Maven multi-module, groupId `com.pagelens`:

- `core`: Graph API client, OAuth/tokens, entities, repositories, services, Flyway migrations (`db/migration`). **Must not depend on Spring Web or MCP.**
- `app-web`: REST API (`/api/...`) and actuator. It will also serve the Vue build (`frontend/`, from Phase 1).
- `app-mcp`: stdio MCP server named `pagelens`. The MCP Java SDK is added in Phase 2; for now the process boots and exits.

Both app main classes live in package `com.pagelens`, so component, entity and repository scanning picks up `com.pagelens.core.*` without extra annotations. Keep new code under `com.pagelens.core.*` (core), `com.pagelens.web.*` and `com.pagelens.mcp.*`.

Configuration: shared settings live in `core/src/main/resources/config/application.yml`. Spring Boot loads that classpath location automatically, with higher precedence than each app's own `application.yml`, so there is no `spring.config.import` for it. Don't redefine shared keys in the app yml files; they would lose. That shared file imports `.env` (as properties; path overridable with `PAGELENS_ENV_FILE`) and `./application-local.yml`. Never add a `config/application.yml` to an app module, because it would shadow core's. `pagelens.*` keys bind to `PageLensProperties`.

Dependency versions: the parent `pom.xml` overrides several Spring Boot-managed versions for security fixes (jackson, logback 1.6, json-smart, asm; android-json is swapped for org.json). Drop each override once a Boot release manages an equal or newer version.

Logging: both apps include `core/src/main/resources/logback-pagelens-files.xml`, which writes JSON (logstash format) to `app.log`, `api-calls.log` (logger `pagelens.api-calls`) and `audit.log` (logger `pagelens.audit`). Use `CorrelationId.open(prefix)` to scope sync runs and MCP calls. HTTP requests get one from `CorrelationIdFilter`.

`app-web` and `app-mcp` are separate processes sharing one database, so rate limiting must be coordinated globally (UI + scheduler + MCP), not per process.

## Critical constraints

- **Never write to stdout in `app-mcp`**: no banner, no `System.out`, no console appender on stdout. Its logback config sends console output to stderr and silences logback's own status printer. `PageLensMcpApplicationTest` asserts stdout stays empty; keep it passing.
- **Never log tokens or secrets.** Mask them in URLs and headers. Keep comment text out of logs.
- Accounts can use either Meta setup: Instagram Login (`graph.instagram.com`) or Facebook Login (`graph.facebook.com`, needs a linked Page). This is stored in `ig_account.login_type`. Permission and metric availability differ between the two.
- Store tokens AES-GCM encrypted. The key comes from `PAGELENS_ENCRYPTION_KEY`; never commit `.env` or `application-local.yml`.
- Write actions are Phase 3, off by default (`pagelens.write-actions-enabled`), support `dry_run`, and log every call to `audit_log`. Publishing must be idempotent.
- The dashboard binds to `127.0.0.1` only (owner decision). Don't widen that without revisiting security.
- Database: PageLens shares the `postgres` database and owns only the `pagelens` schema (`PAGELENS_DB_SCHEMA`). Keep SQL unqualified (the pool's search_path handles it) and never create objects in `public`. `FlywayMigrationTest` asserts that.
- Never edit an applied Flyway migration; add a new `V<n>__*.sql`. SQL stays portable (JSON stored as TEXT) so a SQLite mode remains possible.
- Metric columns are nullable: NULL means "not returned by the API", never zero. Keep metric names in one config file and log unknown or missing metrics.
- Timestamps are stored in UTC (TIMESTAMPTZ). Display and analysis use `PageLensProperties.zoneId()` (system default unless configured).
- Captions can be Farsi or Armenian: full Unicode, and Farsi renders right-to-left in the UI.
