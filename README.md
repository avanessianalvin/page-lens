# PageLens

Local-first Instagram insights for your own Business/Creator accounts. PageLens syncs posts, insights, comments, daily account metrics and audience data through Meta's official Instagram Graph API into your own PostgreSQL. It gives you:

- **a dashboard** (Vue): overview, posts, audience, analysis, publishing, logs, settings
- **a REST API** (Spring Boot)
- **an MCP server** (`pagelens`), so Claude Desktop and Claude Code can answer questions about your accounts, and moderate comments or publish if you allow it

Everything runs on your machine. Tokens are stored encrypted and never leave it.

## Quick start

1. Prerequisites: JDK 21+, PostgreSQL, Node 22.18+, Docker (for tests only). See [docs/SETUP.md](docs/SETUP.md) §1.
2. Database: `psql -h localhost -U postgres -d postgres -f scripts/db/create-schema.sql` (SETUP §2).
3. Configuration: `cp .env.example .env` and fill it in, including a Meta app (SETUP §3–5).
4. Build:
   ```
   cd frontend && npm install && npm run build && cd ..
   ./mvnw -DskipTests package
   ```
5. Run: `scripts/pagelens-web.cmd` (Windows) or `scripts/pagelens-web.sh`, then open <http://127.0.0.1:8080>.
6. Optional: connect Claude (SETUP §8), turn on write actions (SETUP §9), and enable the secrets hook with `git config core.hooksPath .githooks`.

## Documentation

| | |
|---|---|
| [docs/SETUP.md](docs/SETUP.md) | Install, Meta app, dashboard login, Claude, write actions |
| [docs/RUNBOOK.md](docs/RUNBOOK.md) | Operating it: build/test, start/stop, sync, backups and restore, alerts, export, logs, troubleshooting |
| [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | Current state, history and open items. Start here when resuming work |
| [docs/META_ACCESS.md](docs/META_ACCESS.md) | Meta's developer site being blocked in Armenia: what it affects, the options, and how many accounts one app serves |
| [docs/API_NOTES.md](docs/API_NOTES.md) | What Meta's API does and doesn't do, verified against Meta's docs |
| [CHANGELOG.md](CHANGELOG.md) | What each phase added, and the decisions behind it |
| [docs/Instagram Insights MCP Service Project Plan.md](docs/Instagram%20Insights%20MCP%20Service%20Project%20Plan.md) | The original plan |

## Project layout

```
core/       Instagram client, sync, analysis, write actions, backups, alerts, export, Flyway migrations
app-web/    REST API, security, scheduler, serves the built dashboard
app-mcp/    stdio MCP server for Claude
frontend/   Vue 3 dashboard (builds into app-web)
scripts/    launchers, database setup
.githooks/  pre-commit secrets check
```
