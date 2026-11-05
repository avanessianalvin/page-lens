# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

PageLens is a local-first Instagram Insights service (Instagram Graph API → PostgreSQL → MCP server + REST API + Vue dashboard). **No code exists yet.** The full spec is `docs/Instagram Insights MCP Service Project Plan.md` (the plan says it may be placed at the repo root as `PLAN.md`). Read it before starting any phase; it is the source of truth for schema, REST endpoints, MCP tool names, and dashboard pages.

Build/test/run commands are not defined yet. The build tool (Maven or Gradle) is an open question for the owner. Once it's chosen and scaffolded, update this file with the real commands, including how to run a single test.

## Working rules from the plan

- Build phase by phase (0: foundation, 1: read-only core, 2: MCP + analysis, 3: write actions, 4: hardening). Start with Phases 0–1 only, and stop after each phase to summarize what exists.
- Before writing any Instagram API call, check Meta's current docs for the endpoint, permission, and metric names, and record what you find in `docs/API_NOTES.md`. Metric names change often, so don't trust memory.
- Ask the owner before any scope-changing decision (switching database, building write features early, etc.). Open questions are listed in §15 of the plan.
- Keep `CHANGELOG.md` and `docs/RUNBOOK.md` up to date. Meta app setup goes in `docs/SETUP.md`.
- Write tests alongside each service, using recorded API fixtures. Use a mock Instagram server for integration tests and Testcontainers for PostgreSQL.

## Architecture (planned)

Stack: Java 21, Spring Boot 3.x, Spring Data JPA, Flyway, PostgreSQL, official MCP Java SDK. Frontend: Vue 3 + Vite + TypeScript + Pinia + PrimeVue.

Multi-module repo:
- `core`: Graph API client (rate limiting, retries), OAuth/token management, entities, repositories, sync/analytics/publishing services, and Flyway migrations. **Must not depend on Spring Web or MCP.**
- `app-web`: Spring Boot REST API (`/api/...`) behind Spring Security form login. It also serves the built frontend. springdoc-openapi generates the spec that the frontend's typed API client is built from.
- `app-mcp`: a thin stdio MCP server (named `pagelens`) that wraps `core`.
- `frontend/`: the Vue dashboard.

`app-web` and `app-mcp` run as separate processes that share one PostgreSQL database. Because of this, rate limiting needs a global request queue so the UI, scheduler, and MCP together can't burst past Meta's limits.

## Critical constraints

- **Never write to stdout in `app-mcp`.** stdout carries the MCP protocol. Send logs to files and stderr only.
- **Never log tokens or secrets.** Mask them in URLs and headers. Keep comment text out of logs.
- Store tokens encrypted with AES-GCM. The key and the app secret come from env vars (`.env` is git-ignored; ship a `.env.example`). `application-local.yml` is git-ignored too.
- Write actions (publish, reply, hide, delete comments) are disabled by default via a config flag, support `dry_run`, and log every call to `audit_log`. Publishing must be idempotent: store the container id and check its status before retrying.
- Keep metric names in a single config file. Log unknown or missing metrics instead of treating them as zero.
- Store timestamps in UTC, display them in the owner's time zone, and use that zone for best-time analysis.
- Captions can be in Farsi or Armenian. Use full Unicode and render Farsi right-to-left in the UI.
- Sync must use idempotent upserts. Stories need polling every few hours because their insights disappear after 24 hours. Store raw API JSON where it's cheap to do so.
- Keep SQL portable so a lightweight SQLite mode stays possible.
