# Project status and handoff

The state of PageLens for whoever picks up next (a new session or a person). Read this first, then the documents it links to. Last updated: **2026-11-06**.

## Where things stand

| Area | State |
|---|---|
| Plan phases 0–4 (foundation, read-only core, MCP + analysis, write actions, hardening) | **Done and committed** by the owner (commits `phase 1` … `phase 4`). Details: [CHANGELOG.md](../CHANGELOG.md). |
| Meta developer app | **Not created.** `developers.facebook.com` answers "Meta for Developers is not available in this location" from Armenia. See [META_ACCESS.md](META_ACCESS.md). |
| Read-only sources: Windsor.ai sync and Meta Business Suite CSV import | **Built and tested, not committed yet.** Not yet tried against the real services. CHANGELOG has an "Read-only data sources" entry. |
| Tests | Last full run: core 98, app-web 24, app-mcp 11. All passed, none skipped (Docker was up). Every module is above the 70 % line-coverage floor. |

## Timeline

1. **Phase 0, foundation.** Maven multi-module project: `core`, `app-web`, `app-mcp`, plus `frontend/`.
   - Spring Boot 4.1.2 on Java 21.
   - PostgreSQL 17, with a `pagelens` schema inside the existing `postgres` database (`scripts/db/create-schema.sql`).
   - Flyway, token encryption, logging, SETUP and RUNBOOK docs.
2. **Phase 1, read-only core.** Graph API client (Instagram Login and Facebook Login), OAuth, the sync engine and scheduler, REST API, Vue dashboard, and form login.
3. **Phase 2, MCP and analysis.** A stdio MCP server named `pagelens` with 14 read tools plus `sync_now`, the analysis rules, and the Overview, Analysis and Audience pages.
4. **Phase 3, write actions.** Reply to, hide and delete comments, and publish (image, Reel, story, carousel).
   - Guarded by `PAGELENS_WRITE_ACTIONS_ENABLED`; the MCP write tools also need `PAGELENS_MCP_WRITE_TOOLS`.
   - Every action has a dry run and is audited. Publish jobs are idempotent.
5. **Phase 4, hardening.**
   - Nightly `pg_dump` backups, alerts, CSV/JSON export, purge per account.
   - Settings page, dark theme, "Sync all".
   - Pre-commit secrets hook, JaCoCo coverage floor.
6. **Meta access problem.** Meta's App Dashboard is blocked in Armenia. The Graph API hosts themselves are reachable (tested). The analysis, options and risks (a VPN, an app owned by a trusted person, Meta support) are in [META_ACCESS.md](META_ACCESS.md). The owner was told a VPN probably breaks Meta's terms; that decision is theirs.
7. **Read-only sources (owner-approved scope change).**
   - **A. Windsor.ai:** login type `WINDSOR`, code in `core/.../windsor/`.
     - It syncs through the Windsor connector API using `WINDSOR_API_KEY`.
     - Accounts → "Add from Windsor.ai" adds the accounts from your Windsor workspace.
   - **D. CSV import:** login type `IMPORT`, `core/.../importer/BusinessSuiteImporter`.
     - Accounts → "Import CSV" loads a Meta Business Suite content export.
   - Both are read-only. REST endpoints are under `/api/sources`.

## Open items

### Owner actions

- [ ] **Commit** the read-only sources work (see `git status`). The owner always commits; never commit for them.
- [ ] **Rebuild the jar:** stop app-web, run `./mvnw -DskipTests package`, then start it again. The last `verify` couldn't replace `app-web/target/pagelens-web.jar` while it was in use.
- [ ] **Windsor.ai trial:**
  1. Sign up and connect the Instagram account under the "Instagram" source.
  2. Set `WINDSOR_API_KEY` in `.env` and restart app-web.
  3. Click Accounts → Add from Windsor.ai.
  4. Report any `PARTIAL` or `FAILED` sync errors from Logs → Sync runs.
  5. After the 30-day trial, check that syncs still work: Windsor doesn't say whether the free plan includes the API.
- [ ] **CSV import:** try a real Business Suite export. If columns are missing, share only its header row.
- [ ] **Security:**
  - Rotate the DB password; the real one is in `scripts/db/create-schema.sql`, which was pushed.
  - Enable the hook with `git config core.hooksPath .githooks`.
  - The owner **declined** reverting the bcrypt hash in `.env.example` and the password in `create-schema.sql`. Don't ask again. The hook will block the hash line unless it's marked `pagelens:allow-secret` or the commit uses `--no-verify`.
- [ ] Switch the system `JAVA_HOME` to JDK 21. The default is still 17, at `C:\Program Files\Java\jdk-21`.

### Development, once real data arrives

- [ ] Adjust `WindsorMapper` field groups and parsing to Windsor's real responses. Record what you find in API_NOTES ("Verified 2026-11-06: Windsor.ai" and "Still to verify").
- [ ] Adjust the column aliases in `BusinessSuiteImporter` to a real export.
- [ ] Work through the rest of API_NOTES' "Still to verify" list. Most of it needs a direct Meta connection.

### Not built (options only)

- Metricool API, Make.com, and Instagram "Download your information" import. Compared in META_ACCESS.md. Each would need the owner's approval.

## Environment facts (this machine)

- Windows 10. The build needs JDK 21: `export JAVA_HOME="/c/Program Files/Java/jdk-21"` in Git Bash. Python isn't installed; perl and PowerShell are.
- PostgreSQL 17 runs locally. Docker Desktop is used only by the tests (Testcontainers).
- `.env` (git-ignored) holds the DB settings, `PAGELENS_WEB_USERNAME` and `PAGELENS_WEB_PASSWORD_HASH`. It has no Meta app credentials and no Windsor key yet. A copy of the earlier version is at `%TEMP%\env.backup`.
- The owner is in Yerevan, Armenia. A proxy app had once left `127.0.0.1:2080` in the Windows proxy settings.

## Where to look

| Need | Document |
|---|---|
| Rules, architecture, constraints | [CLAUDE.md](../CLAUDE.md) |
| What was built, phase by phase, and why | [CHANGELOG.md](../CHANGELOG.md) |
| Install, Meta app, Claude MCP setup | [SETUP.md](SETUP.md) |
| Running it: sync, backups, troubleshooting | [RUNBOOK.md](RUNBOOK.md) |
| Meta and Windsor API facts, and what's still unverified | [API_NOTES.md](API_NOTES.md) |
| The Armenia block, alternatives, free-plan limits | [META_ACCESS.md](META_ACCESS.md) |
| The original spec | [Instagram Insights MCP Service Project Plan.md](Instagram%20Insights%20MCP%20Service%20Project%20Plan.md) |
