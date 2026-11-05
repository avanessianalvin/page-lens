# Instagram Insights MCP Service: Project Plan

This document is the spec for Claude Code (CLI). Place it in the project root as `PLAN.md` and ask Claude Code to build the project phase by phase. Verify every Instagram endpoint, metric name, permission and version against Meta's current developer docs before coding, because metric names and limits change often.

## 1. Goals

- Pull all useful data about the owner's Instagram Business/Creator accounts through the official Instagram Graph API (free, no third-party middleman).
- Support multiple Instagram accounts.
- Store history locally so analysis does not depend on API limits or on Instagram's short retention of some metrics.
- Expose the same capabilities three ways: an MCP server for Claude, a REST API, and a Vue.js dashboard for manual use.
- Provide detailed, searchable logs of everything the system does.
- Run entirely on the owner's machine. Tokens never leave it.

## 2. Core Capabilities

**Read (phase 1, build first)**

- List accounts and basic profile info (followers, media count).
- List posts with caption, timestamp, media type, permalink, like and comment counts.
- Per-post insights: reach, views, likes, comments, shares, saves, follows gained, plus watch-time metrics for Reels where available.
- Comments on a post, including replies.
- Account insights: daily reach, follower count changes, audience breakdown by country, city, age, gender.
- Stories (only available for a short window, so collect them regularly).

**Write (phase 3, behind confirmation)**

- Publish photo, carousel, Reel, Story using the container workflow: create container, poll status until FINISHED, then publish.
- Reply to comments, hide or unhide comments, delete comments.
- Limited by the API: editing captions after publishing and deleting posts are generally not supported. Document these gaps in the UI rather than faking them.

**Analysis helpers (phase 2)**

- Top and bottom N posts by any metric.
- Format comparison (Reel vs photo vs carousel) on reach and engagement rate.
- Best posting days and hours.
- Follower growth per post, with a daily-snapshot fallback.
- Audience split by country and language.
- Caption analysis inputs: length, language, hashtags, call to action.

## 3. Tech Stack

- **Backend:** Java 21, Spring Boot 3.x, Spring Web, Spring Data JPA, Spring Scheduling, Spring Security, Flyway for migrations.
- **HTTP client:** Spring WebClient or Java's built-in HttpClient. Jackson for JSON.
- **Database:** PostgreSQL (already installed). Keep SQL portable so SQLite is possible for a lightweight mode.
- **MCP:** the official MCP Java SDK, using stdio transport for Claude Desktop and Claude Code, with optional HTTP transport later.
- **Frontend:** Vue 3, Vite, TypeScript, Pinia, Vue Router, a charting library such as Chart.js or ECharts, and a component library such as PrimeVue.
- **Logging:** SLF4J with Logback, JSON structured output, plus an audit table in the database.
- **Build:** Maven or Gradle (pick one), with a single multi-module repo.

## 4. Architecture

One repository, three modules:

- `core`: Instagram API client, domain model, services, repositories, token management, sync jobs. No web or MCP code here.
- `app-web`: Spring Boot application exposing the REST API for the Vue UI and serving the built frontend.
- `app-mcp`: a thin runnable that uses `core` and exposes the MCP tools over stdio.

Both `app-web` and `app-mcp` share the same PostgreSQL database, so data synced by one is visible to the other. Important for stdio: **in the MCP process, never write logs to stdout**, because stdout carries the protocol. Send logs to files and stderr only.

### Suggested folder layout

```
instagram-insights/
  PLAN.md
  pom.xml
  core/
    src/main/java/.../client/        Instagram Graph API client, rate-limit handling
    src/main/java/.../auth/          OAuth flow, token storage, refresh
    src/main/java/.../model/         entities and DTOs
    src/main/java/.../repo/          repositories
    src/main/java/.../service/       sync, analytics, publishing services
    src/main/resources/db/migration/ Flyway scripts
  app-web/
    src/main/java/.../web/           REST controllers, security config
    src/main/resources/application.yml
  app-mcp/
    src/main/java/.../mcp/           MCP server, tool definitions
  frontend/
    src/views/ src/components/ src/stores/ src/api/
  docs/
    SETUP.md  API_NOTES.md  RUNBOOK.md
```

## 5. Authentication and Meta Setup

**One-time setup (document in `docs/SETUP.md`)**

1. Create a Meta developer app at developers.facebook.com. Name it PageLens (or PageLens Dev) so it's recognizable in the Meta dashboard.
2. Add the Instagram product. Convert each Instagram account to Business or Creator.
3. Link each Instagram account to a Facebook Page if the chosen login flow requires it.
4. Add yourself as an app admin/tester. For accounts you own, development mode is enough and avoids full app review.
5. Request the needed permissions: basic access, insights, comment management, content publishing. Confirm exact names in current docs.

**Token handling**

- Use the OAuth flow with a redirect to the local app, for example `http://localhost:8080/auth/callback`. Confirm Meta accepts a localhost redirect for dev apps.
- Exchange the short-lived token for a long-lived one, and refresh it before it expires (long-lived tokens last about 60 days).
- Store tokens **encrypted** in the database (AES-GCM, with the key held in an environment variable or OS keychain, never in the repo).
- Keep the app secret and encryption key in environment variables. Provide `.env.example` and add `.env` to `.gitignore`.
- A scheduled job checks token expiry daily and warns in the UI and logs when a token is within 7 days of expiring.

**Login for the dashboard (the app's own login)**

- Single-user setup: Spring Security form login with a bcrypt-hashed password stored in config or the database, bound to localhost only by default.
- Session cookie, CSRF protection, and rate limiting on login attempts.
- Optional later: multiple users with roles (admin, viewer).
- MCP over stdio needs no login because it runs as a local child process of Claude. If HTTP transport is added, require a bearer token.

## 6. Database Schema (PostgreSQL)

Use Flyway migrations. Core tables:

- `ig_account`: id, ig\_user\_id, username, display\_name, account\_type, followers\_count, media\_count, created\_at.
- `ig_token`: account\_id, encrypted\_token, token\_type, expires\_at, last\_refreshed\_at, status.
- `media`: id, account\_id, ig\_media\_id, media\_type, caption, permalink, posted\_at, thumbnail\_url, is\_story, raw\_json, first\_seen\_at.
- `media_insight_snapshot`: id, media\_id, captured\_at, reach, views, likes, comments, shares, saves, follows, plus a JSON column for extra metrics. **Snapshots over time** show how a post performs in its first hours versus later.
- `comment`: id, media\_id, ig\_comment\_id, parent\_id, username, text, like\_count, posted\_at, hidden.
- `account_daily_metric`: account\_id, day, reach, followers\_count, follower\_delta, profile\_views, and other daily values.
- `audience_snapshot`: account\_id, captured\_at, dimension (country, city, age, gender), key, value.
- `sync_run`: id, account\_id, job\_type, started\_at, finished\_at, status, items\_fetched, error\_message.
- `api_call_log`: id, account\_id, endpoint, http\_status, duration\_ms, rate\_usage\_header, error\_code, created\_at.
- `audit_log`: id, actor (ui, mcp, scheduler), action, target, payload\_summary, created\_at. Every write action goes here.

Store the raw JSON of API responses where cheap, so new fields can be extracted later without re-calling the API.

## 7. Sync Engine

- A scheduled job runs about twice a day (configurable per account) to pull new posts, refresh insights for recent posts, pull comments for recent posts, and snapshot daily account metrics and audience data.
- **Insight snapshots:** refresh posts less than 7 days old on every run, older posts less often.
- **Stories:** poll every few hours, because they expire after 24 hours and insights vanish.
- **Backfill:** an on-demand job to fetch all historical posts for a new account.
- Use pagination cursors, retries with exponential backoff, and idempotent upserts so reruns never duplicate data.
- Respect rate limits: read the usage headers Meta returns, slow down when usage is high, and record it in `api_call_log`.
- Manual "Sync now" button in the UI and a matching MCP tool.

## 8. REST API (for the Vue UI and manual use)

Prefix `/api`. All endpoints require the dashboard session.

- `GET /accounts`, `POST /accounts/connect`, `DELETE /accounts/{id}`
- `GET /accounts/{id}/posts` with filters for date, type, and sort by metric
- `GET /posts/{id}` with details, latest insights, and snapshot history
- `GET /posts/{id}/comments`, `POST /comments/{id}/reply`, `POST /comments/{id}/hide`, `DELETE /comments/{id}`
- `GET /accounts/{id}/insights/daily`, `GET /accounts/{id}/audience`
- `GET /accounts/{id}/analysis/top-posts`, `/analysis/formats`, `/analysis/timing`, `/analysis/growth`
- `POST /accounts/{id}/sync`, `GET /sync-runs`
- `POST /publish` (create and publish), `GET /publish/{id}/status`
- `GET /logs/api-calls`, `GET /logs/audit`, `GET /logs/app` (tail with filters)
- `GET /health` and `GET /rate-limit`
- `GET /export/posts.csv` and `/export/posts.json`

## 9. MCP Tools

Name the server pagelens in the MCP config so it's recognizable in Claude Desktop and Claude Code. Keep tool names clear and descriptions precise, since Claude chooses tools from them. Read tools return compact JSON and accept a `limit` to avoid flooding the context.

**Read tools**

- `list_accounts`
- `get_account_overview` (followers, recent growth, last sync)
- `list_posts` (account, limit, date range, type, sort\_by)
- `get_post_details` (post id, includes insights and snapshot history)
- `get_post_comments` (post id, limit)
- `get_top_posts` and `get_bottom_posts` (metric, count, period)
- `compare_formats`
- `get_best_posting_times`
- `get_audience_breakdown` (dimension: country, city, age, gender)
- `get_follower_growth` (per post or daily)
- `get_sync_status`

**Action tools**

- `sync_now` (account)
- `reply_to_comment`, `hide_comment` (marked as write actions)
- `publish_post` (marked as write action)

**Safety rules for action tools:** disabled by default via a config flag, every call written to `audit_log`, and a `dry_run` option that validates without publishing. Claude clients normally ask the user to approve write tools, but do not rely on that alone.

Also expose **MCP resources** for a few static items such as the metric glossary, so Claude interprets numbers correctly, and optionally **prompts** such as a weekly performance review template.

## 10. Vue.js Dashboard

Pages:

1. **Login**
2. **Accounts:** connect via OAuth, token status and expiry warning, last sync time.
3. **Overview:** follower trend, reach trend, best and worst recent posts, sync status.
4. **Posts:** sortable and filterable table with thumbnails; click a row for details.
5. **Post detail:** all metrics, insight snapshots over time as a chart, comments with reply and hide controls, link to the post on Instagram.
6. **Audience:** charts by country, city, age, gender, with a note when a group is too small to trust.
7. **Analysis:** format comparison, timing heatmap by weekday and hour, caption stats.
8. **Publish:** upload or URL form, preview, schedule, dry run.
9. **Logs:** tabs for application log, API calls, audit trail, and sync runs, with filters and live refresh.
10. **Settings:** sync frequency, enable or disable write actions, export data, theme.

Use a typed API client generated from an OpenAPI spec (springdoc-openapi) so the frontend and backend stay in sync. Use "PageLens" as the dashboard title and browser tab name.

## 11. Logging and Observability

- Structured JSON logs with a correlation id per request, sync run, or MCP call.
- Separate log files: `app.log`, `api-calls.log`, `audit.log`, with rotation and size limits.
- Every Instagram API call records endpoint, status, duration, and rate-limit usage. **Never log access tokens**; mask them in any URL or header.
- Log levels configurable at runtime through Spring Boot Actuator.
- The UI Logs page reads from the database tables and the log files.
- Optional: a health endpoint plus a simple alert in the UI when a sync fails twice in a row.

## 12. Corners You May Not Have Thought Of

- **Data availability windows:** story insights vanish after the story expires, and some metrics only cover recent periods. Collect regularly or the data is lost.
- **Metric churn:** Meta renames and deprecates metrics. Keep metric names in one config file and log unknown or missing fields rather than treating them as zero.
- **Small-sample warning:** flag audience groups under about 30 people as unreliable.
- **Time zones:** store UTC, display in the owner's time zone, and use that zone for the best-time analysis.
- **Multi-language content:** Farsi and Armenian captions need full Unicode, right-to-left rendering in the UI for Farsi, and a language detection step for caption analysis.
- **Sensitive content and location:** comments can contain personal data. Keep the database local, offer a purge command per account, and exclude comment text from logs.
- **Token expiry and revocation:** handle the "token invalid" error gracefully, mark the account as needing reconnection, and tell the user clearly.
- **Meta policy compliance:** follow the platform terms, do not scrape, and delete data when an account is disconnected.
- **Backups:** nightly `pg_dump` script and a documented restore procedure.
- **Secrets hygiene:** `.gitignore` for env files, no secrets in tests, and a pre-commit check.
- **Rate limits under load:** a global request queue so the UI, scheduler, and MCP cannot burst past the limit together.
- **Idempotent publishing:** never double-publish on retry; store the container id and check status first.
- **Testing:** unit tests with recorded API fixtures, a mock Instagram server for integration tests, and Testcontainers for PostgreSQL.
- **Config via profiles:** `dev` and `prod` profiles, with an `application-local.yml` that is git-ignored.
- **Cross-platform launch:** provide start scripts, and document the Claude Desktop config entry that launches the MCP jar.

## 13. Delivery Phases

**Phase 0: Foundation**

- Repo, modules, Flyway, PostgreSQL connection, logging setup, `docs/SETUP.md`, Meta app creation walkthrough.

**Phase 1: Read-only core**

- OAuth and token storage, Instagram client, sync for posts, insights, comments, daily metrics, audience. REST endpoints for reading. Basic Vue pages: login, accounts, posts, post detail, logs.

**Phase 2: MCP and analysis**

- MCP server with all read tools, analysis queries, Vue overview, audience and analysis pages. Connect to Claude Desktop and Claude Code and test with real questions.

**Phase 3: Write actions**

- Comment reply and hide, publishing with container workflow, audit trail, confirmation dialogs, dry run mode.

**Phase 4: Hardening**

- Backups, alerts, export, test coverage, multi-account polish, documentation.

## 14. Instructions for Claude Code

When building from this plan:

1. Start with Phase 0 and Phase 1 only. Stop after each phase and summarize what exists.
2. Before writing any Instagram call, check Meta's current documentation for the endpoint, required permission, and metric names, and record findings in `docs/API_NOTES.md`.
3. Keep `core` free of Spring web and MCP dependencies.
4. Never print or log tokens or secrets. Never write to stdout in the MCP module.
5. Write tests alongside each service, using recorded fixtures.
6. Ask the owner before making any decision that changes scope, such as switching database or adding write features early.
7. Keep a `CHANGELOG.md` and update `docs/RUNBOOK.md` with how to start, stop, back up, and troubleshoot.

## 15. Open Questions for the Owner

- How many Instagram accounts, and are they all Business or Creator accounts linked to Facebook Pages?
- Is the dashboard strictly local, or might it be exposed on a network later?
- Should write actions be built at all in the first version?
- Preferred build tool: Maven or Gradle?
- Which time zone should analysis use?
