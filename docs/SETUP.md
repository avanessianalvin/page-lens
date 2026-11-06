# PageLens Setup

One-time setup for a local PageLens install. For day-to-day operation see [RUNBOOK.md](RUNBOOK.md).

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21+ | Build and run need JDK 21. If `JAVA_HOME` points to an older JDK, set it for the shell: PowerShell `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"`, Git Bash `export JAVA_HOME="/c/Program Files/Java/jdk-21"`. The build fails fast with a clear message on older JDKs. |
| Maven | none needed | Use the bundled wrapper: `./mvnw` (or `mvnw.cmd`). |
| PostgreSQL | 17 (any 14+) | Must be running locally on port 5432 (or set `PAGELENS_DB_URL`). |
| Docker | any recent | Only for integration tests (Testcontainers). Without Docker those tests are skipped, not failed. |
| Node.js | 22.18+ or 24.12+ | Dashboard build. The Vite toolchain declares this minimum. 22.17 built fine on 2026-11-05, but update if you see engine errors. |

## 2. Database

PageLens doesn't need its own database. It uses a dedicated **`pagelens` schema inside the existing `postgres` database**, owned by a `pagelens` login role. All PageLens tables, including `flyway_schema_history`, live in that schema, and nothing is written to `public`.

With the PostgreSQL service running, run this as the `postgres` superuser (it's safe to re-run):

```
psql -h localhost -U postgres -d postgres -f scripts/db/create-schema.sql
```

The script sets the role's password. `.env` must contain the same value in `PAGELENS_DB_PASSWORD`. To change it later, run `ALTER ROLE pagelens PASSWORD '...'`.
Flyway creates and upgrades all tables when either app starts. Never create tables by hand.

## 3. Configuration

```
cp .env.example .env
```

Fill in `.env` (it is git-ignored). Real environment variables override `.env`.
Optional per-machine Spring overrides go in `application-local.yml` at the repo root (also git-ignored, read by app-web).

| Variable | Purpose |
|---|---|
| `PAGELENS_DB_URL`, `PAGELENS_DB_USER`, `PAGELENS_DB_PASSWORD` | Database connection (default URL: the `postgres` database on localhost:5432) |
| `PAGELENS_DB_SCHEMA` | Schema holding all PageLens tables (default `pagelens`) |
| `PAGELENS_LOG_DIR` | Log directory. Use an absolute path so the MCP process (started by Claude from any directory) writes to the same place. |
| `PAGELENS_TIME_ZONE` | IANA zone for display and best-time analysis. Empty means this machine's zone. Storage is always UTC. |
| `PAGELENS_ENV_FILE` | Path to the `.env` file. Default is `.env` in the working directory. Set it to an absolute path for the MCP process. |
| `PAGELENS_WEB_PORT` | Dashboard port (default 8080). The dashboard binds to 127.0.0.1 only. |
| `META_*`, `PAGELENS_ENCRYPTION_KEY` | Meta app credentials and the token encryption key, used from Phase 1 (see below). |

## 4. Meta developer app

PageLens talks to the official Instagram Graph API. Meta offers two setups, and PageLens supports both, chosen per account:

| | Instagram API with **Instagram Login** | Instagram API with **Facebook Login** |
|---|---|---|
| Facebook Page needed | No | Yes. The IG account must be linked to a Page you can administer. |
| User logs in with | Instagram credentials | Facebook credentials |
| API host | `graph.instagram.com` | `graph.facebook.com` |
| Read permissions | `instagram_business_basic`, `instagram_business_manage_insights` | `instagram_basic`, `instagram_manage_insights`, `pages_show_list`, `pages_read_engagement` |
| Comment permission | `instagram_business_manage_comments` | `instagram_manage_comments` |
| Publish permission (Phase 3) | `instagram_business_content_publish` | `instagram_content_publish` |

Names were checked against Meta's docs on 2026-11-05 (see [API_NOTES.md](API_NOTES.md)). Recheck them before Phase 1, because Meta renames permissions often. Meta's overview also mentions a newer "Unified Login" option for new integrations; Phase 1 will evaluate it.

### Steps

1. Go to <https://developers.facebook.com/apps>, click **Create app**, and name it **PageLens** (or **PageLens Dev**). Choose the business use case that offers the Instagram product.
2. Add the **Instagram** product to the app.
3. Make sure each Instagram account is a **Business** or **Creator** (professional) account: Instagram app → Settings → Account type and tools.
4. For accounts that will use Facebook Login, link the account to a Facebook Page: Page settings → Linked accounts → Instagram.
5. Under **App roles**, add yourself as an admin. Under the Instagram product, add each Instagram account as an **Instagram tester** and accept the invite in the Instagram app (Settings → Apps and websites → Tester invites).
6. Stay on **Standard Access**. Meta's docs say Standard Access is enough when the app only serves accounts you own or manage and that have a role on the app. App Review and Business Verification are only needed for Advanced Access, which we don't need.
7. Add the permissions from the table above. Leave out the publish permissions until Phase 3.
8. Add the OAuth redirect URI `http://localhost:8080/auth/callback`. *Not yet verified:* Meta's Instagram Login docs only show HTTPS examples. If plain `http://localhost` is rejected, Phase 1 will add a local HTTPS option. See API_NOTES.md.
9. Copy the credentials into `.env`. The app ID and secret go in `META_APP_ID` / `META_APP_SECRET`. For Instagram Login, the separate **Instagram app ID / secret** from the Instagram product's settings go in `META_INSTAGRAM_APP_ID` / `META_INSTAGRAM_APP_SECRET`.
10. Generate the token encryption key and put it in `PAGELENS_ENCRYPTION_KEY`:
    ```
    openssl rand -base64 32
    ```
    If you lose this key, stored tokens can't be decrypted and every account has to be reconnected. Back it up separately from the database.

## 5. Dashboard login

The dashboard has one user. Build once, then generate a bcrypt hash of your chosen password (at least 10 characters):

```
./mvnw -DskipTests package
java -jar app-web/target/pagelens-web.jar --hash-password
```

Copy the printed `PAGELENS_WEB_PASSWORD_HASH=...` line into `.env` as-is. The username defaults to `admin` (`PAGELENS_WEB_USERNAME`). Without a valid hash, app-web refuses to start and says why. Five failed logins lock the client out for 15 minutes.

## 6. Build and start

```
cd frontend && npm install && npm run build && cd ..   # builds the dashboard into app-web
./mvnw -DskipTests package                             # bundles it into the jar
scripts/pagelens-web.cmd      (Windows; or scripts/pagelens-web.sh. Both run app-web/target/pagelens-web.jar)
```

The launcher sets `PAGELENS_ENV_FILE`, `PAGELENS_LOG_DIR`, `PAGELENS_BACKUP_DIR` and `PAGELENS_UPLOAD_DIR` relative to the repo, so it works from any folder. It picks Java from `PAGELENS_JAVA`, then `JAVA_HOME` (both must be JDK 21+).

Open <http://127.0.0.1:8080>, log in, and go to **Accounts**:

- **Connect with Instagram / Connect with Facebook** runs the OAuth flow. Meta redirects back to `PAGELENS_OAUTH_REDIRECT_URI`, which must exactly match the App Dashboard entry.
- **Paste a token** is the fallback if Meta rejects the localhost redirect. Generate a token in the App Dashboard (*Instagram → API setup with Instagram business login → Generate token*, valid 60 days) and paste it. Instagram Login tokens are refreshed automatically after that.

The first sync starts automatically after connecting. It fetches all posts (Meta's cap is the newest 10K), insights, recent comments, 90 days of account metrics and audience demographics. Later syncs run on `PAGELENS_SYNC_CRON` (06:00 and 18:00 by default), stories every 3 hours.

## 7. Verify the install

```
./mvnw verify            # build + tests (integration tests need Docker)
```

`http://127.0.0.1:8080/actuator/health` returns `{"status":"UP"}`. Logged in, it also shows the database status.

## 8. Connect Claude (MCP)

The MCP server is `app-mcp/target/pagelens-mcp.jar`. Claude starts it as a child process and talks to it over stdin/stdout. It reads the same database as the dashboard, so keep app-web running for scheduled syncs; the MCP process never syncs on its own (except through its `sync_now` tool).

It needs **JDK 21** (your default `java` may be older, so use the full path) and two settings so it finds your `.env` and log folder from any working directory:

| Variable | Value |
|---|---|
| `PAGELENS_ENV_FILE` | absolute path to `.env`, e.g. `K:/projects/page-lens/.env` |
| `PAGELENS_LOG_DIR` | absolute path for logs, e.g. `K:/projects/page-lens/logs` (shared with app-web) |

### Claude Code

```
claude mcp add pagelens --scope user \
  -e PAGELENS_ENV_FILE=K:/projects/page-lens/.env \
  -e PAGELENS_LOG_DIR=K:/projects/page-lens/logs \
  -- "C:/Program Files/Java/jdk-21/bin/java.exe" -jar K:/projects/page-lens/app-mcp/target/pagelens-mcp.jar
```

Check it with `claude mcp list`. Inside a session, `/mcp` shows the server and its 14 tools.

### Claude Desktop

Edit `%APPDATA%\Claude\claude_desktop_config.json` (Settings → Developer → Edit Config), add the entry, and restart Claude Desktop:

```json
{
  "mcpServers": {
    "pagelens": {
      "command": "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe",
      "args": ["-jar", "K:\\projects\\page-lens\\app-mcp\\target\\pagelens-mcp.jar"],
      "env": {
        "PAGELENS_ENV_FILE": "K:\\projects\\page-lens\\.env",
        "PAGELENS_LOG_DIR": "K:\\projects\\page-lens\\logs"
      }
    }
  }
}
```

The launchers `scripts/pagelens-mcp.cmd` (Windows) and `scripts/pagelens-mcp.sh` (macOS/Linux/Git Bash) set both variables relative to the repo. They pick Java from `PAGELENS_JAVA`, then `JAVA_HOME`. On Windows, use them as `"command": "cmd", "args": ["/c", "K:\\projects\\page-lens\\scripts\\pagelens-mcp.cmd"]`.

### Try it

Ask Claude things like "Which of my posts did best last month?", "Do Reels or carousels work better for me?", "When should I post?", "Where is my audience?", or use the **weekly_performance_review** prompt. Rebuild the jar (`./mvnw -DskipTests package`) after updating PageLens; Claude restarts the server on its next session.

Set `PAGELENS_MCP_SYNC_TOOL=false` in the MCP server's env to hide `sync_now` (it only reads from Instagram, but it spends rate budget).

## 9. Write actions (optional)

Replying to, hiding and deleting comments, and publishing, are **off by default**. To turn them on:

1. Set `PAGELENS_WRITE_ACTIONS_ENABLED=true` in `.env` and restart app-web.
2. **Reconnect every account** on the Accounts page. Publishing needs a permission that PageLens only requests since Phase 3: `instagram_business_content_publish` (Instagram Login) or `instagram_content_publish` (Facebook Login). Comment moderation was already included. In the Meta App Dashboard, add the publish permission to the app first.
3. Optional, for Claude: add `"PAGELENS_WRITE_ACTIONS_ENABLED": "true"` **and** `"PAGELENS_MCP_WRITE_TOOLS": "true"` to the MCP server's `env` (SETUP.md §8; for Claude Code add `-e` flags). Claude then gets `reply_to_comment`, `hide_comment`, `publish_post` and `get_publish_status`. They run as a dry run unless Claude passes `dry_run=false`, and Claude asks you before running a write tool.

What publishing can and can't do (Meta's rules, see `docs/API_NOTES.md`):

- Photo, Reel, story or carousel (2–20 items), **published immediately**. There's no scheduling.
- **Media comes from a public URL** Instagram downloads (JPEG images ≤ 8 MB; MP4/MOV video). PageLens runs only on your machine, so it can't serve your files to Instagram. For **Facebook Login** accounts, videos can also be uploaded from this computer (Publish page → *Upload file*).
- 50 API-published posts per 24 hours. Captions up to 2200 characters, 30 hashtags and 20 @-mentions.
- Captions can't be edited afterwards, and PageLens doesn't delete posts. Use the Instagram app for both.
- Every action, dry run or real, is in the audit trail (Logs → Audit trail), with the actor `ui` or `mcp`.

## 10. Backups and the secrets hook

- **Backups** run nightly at 03:15 (`PAGELENS_BACKUP_CRON`) while app-web is running. Each one dumps the `pagelens` schema with `pg_dump` into `backups/` and keeps 14 days (`PAGELENS_BACKUP_KEEP_DAYS`). PageLens finds `pg_dump` on PATH or under `C:\Program Files\PostgreSQL\<version>\bin`; otherwise set `PAGELENS_PG_DUMP`. Check *Settings → Backups* after the first night, or press **Back up now**. To restore, see RUNBOOK.md → Backup / restore. **Keep a copy of `PAGELENS_ENCRYPTION_KEY` somewhere safe and separate**: a backup without it can't decrypt the stored tokens (you'd have to reconnect the accounts).
- **Secrets hook:** run `git config core.hooksPath .githooks` once. Commits are then blocked if they contain `.env` files, Instagram or Facebook tokens, private keys, bcrypt hashes, or password/secret values; `change-me` placeholders are fine. Mark a deliberate test value with `pagelens:allow-secret`.
