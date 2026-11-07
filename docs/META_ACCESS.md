# Meta access from Armenia

Notes from 2026-11-06 on a regional restriction we ran into during the Meta app setup ([SETUP.md](SETUP.md) §4).

## The problem

`https://developers.facebook.com/apps` redirects to `https://developers.facebook.com/account_status/error/` with the message
**"Meta for Developers is not available in this location."** It happens with the real Armenian IP (Telecom Armenia, Yerevan), in any browser, with the proxy app turned off.

A stale link can also show "Sorry, the link you followed may be broken". That page appears when the Facebook account isn't registered as a developer yet, or when it was opened from an old error URL. It is a different problem.

## What is blocked and what isn't

Checked from this machine, with no proxy or VPN, on 2026-11-06:

| Host | Used by PageLens for | Reachable from Armenia |
|---|---|---|
| `graph.instagram.com` | Every Instagram Login API call: sync, insights, comments, publishing, token refresh | ✅ Yes. Without a token it answers `Invalid OAuth 2.0 Access Token`, which is the normal reply |
| `graph.facebook.com` | Every Facebook Login API call | ✅ Yes. Normal `OAuthException` reply |
| `api.instagram.com`, `www.instagram.com` | The "Connect account" login page and the code → token exchange | ✅ Yes |
| `developers.facebook.com` | The App Dashboard | ❌ Blocked |

To check it again:

```
curl -s https://graph.instagram.com/v27.0/me
curl -s https://graph.facebook.com/v27.0/me
```

A JSON error about a missing or invalid token means the host is reachable. A geo-block would look different: an HTML page, a connection error, or a "not available" message.

Still unknown: whether Meta also checks the location during the OAuth consent step (the "Connect account" flow). `instagram.com` works from Armenia, so it probably doesn't, but this hasn't been tried with a real app yet.

## Which steps need the App Dashboard

| When | What | Needs `developers.facebook.com` |
|---|---|---|
| Once, at setup | Create the app, add the Instagram product, set permissions, add `http://localhost:8080/auth/callback` as a redirect URI, copy the app ID and secret into `.env` | Yes |
| Once per Instagram account | Send it an **Instagram tester** invite | Yes, to send it |
| | Accept the invite (Instagram app → Settings → Apps and websites → Tester invites) | No |
| Once a year | Meta's **Data Use Checkup**. Meta can restrict the app if it's missed | Yes |
| Occasionally | Change a setting, rotate the app secret, see app notices | Yes |
| Day to day | Connect accounts, scheduled and manual syncs, the dashboard, Claude's MCP tools, write actions | **No** |

So PageLens runs without a VPN. Only the dashboard visits above need access to `developers.facebook.com`.

## Options

1. **Ask Meta.** Use the support or appeal link on the error page, or Meta's developer support. This is the cleanest route if Meta allows it.
2. **A VPN only while using the App Dashboard.** This works technically, because nothing else needs it. But Meta blocks the region on purpose, so working around it is probably against Meta's developer terms. If Meta notices, it can restrict the app or the developer account, and PageLens depends on that account. The decision and the risk are the owner's.
3. **A trusted person outside Armenia owns the app.** They create it, send tester invites to your Instagram accounts, and give you the app ID and secret. You accept the invites inside Instagram, which works from Armenia. The catch: they own the app, so you depend on them for the yearly checkup and for any setting changes.
4. **No Meta app at all: a read-only source.** Built: Windsor.ai (automatic) and the Meta Business Suite CSV import (manual). See the next section.

## Read-only routes that skip `developers.facebook.com`

Researched on 2026-11-06. The owner approved **A** and **D**, and both are built (see "Using the built routes" below). B, C and E are not built.

Any official API access needs *some* Meta app. The idea is to use a service whose Meta app is already approved: you connect your Instagram account to that service through Meta's normal consent screen (`instagram.com` / `facebook.com`, both reachable), and PageLens pulls the data from the service.

| Route | How PageLens would get data | Automatic | Data depth | Cost | Reachable from Armenia (2026-11-06) |
|---|---|---|---|---|---|
| **A. Windsor.ai connector API** | PageLens calls Windsor's REST API (`connectors.windsor.ai/instagram`, API key, JSON) on the sync schedule, instead of the Graph API | Yes | Account and post metrics (views, reach, saves, follower changes…), comments, stories, audience | Free plan: 1 Instagram account, 30 days of history; Basic $19–23/month for up to 75 accounts | ✅ `windsor.ai` 200; API answers `Not authorized` without a key |
| **B. Metricool API** | PageLens calls Metricool's analytics API (`/v2/analytics/{posts, reels, stories}/instagram`, per a third-party connector) | Yes | Posts, Reels, stories, hashtags | Paid; API access is plan-gated, details to confirm | ✅ `app.metricool.com` 200 |
| **C. Make.com scenario** | Make's "Instagram for Business" app (Facebook Login, so a linked Page is needed) runs "Get media insights" or "Make an API call" and writes to a sink PageLens reads. Make can't reach PageLens on `127.0.0.1`, so the sink is a file, sheet or database | Yes | Raw Graph API through Make's app; Make's built-in insights module still used deprecated metrics in 2025 | Free tier, then paid | ✅ `make.com` 200 |
| **D. Meta Business Suite CSV export** | You export Insights → Content as CSV (Posts or Stories, chosen date range) and upload it into PageLens | No, manual | Per-post metrics for the chosen range | Free | ✅ Login page loads (`business.facebook.com`); check it in a browser |
| **E. Instagram "Download your information"** | You request the JSON export in Accounts Center and import it | No, manual, takes hours to days | Posts, captions, comments. Third-party guides say professional accounts also get insights; Instagram's help doesn't confirm it | Free | ✅ (inside Instagram) |

Not acceptable: scraping, unofficial or private Instagram APIs, or tools that log in with your Instagram password. They break Instagram's terms and are a common cause of account locks.

Tradeoffs compared with the planned direct Graph API:

- A third party holds your Instagram tokens and data. PageLens is no longer fully local.
- Field coverage depends on the service. Some things in the plan (audience demographics, story metrics, comments) may be missing or delayed.
- Write actions (Phase 3) wouldn't work through A, D or E.
- The sync layer is the only part that changes. The database, analysis, dashboard and MCP tools stay the same, because they read the local database.

Recommendation: use **A** for automatic syncing of one account on the free plan, and **D** for extra accounts or as a free manual alternative.

### Using the built routes

**A. Windsor.ai**

1. Sign up at windsor.ai (no card needed). Connect the Instagram account under the **Instagram** data source (*not* "Instagram Public Data"). Windsor's own consent screen runs on facebook.com or instagram.com, which work from Armenia.
2. Copy your API key from Windsor (account → API key) into `.env`: `WINDSOR_API_KEY=...`. Restart app-web.
3. In the dashboard, go to Accounts → **Add from Windsor.ai**. The account appears as "Windsor.ai (read-only)" and its first sync starts. After that, the scheduler syncs it like any other account.

**What the free plan gives you** (checked on Windsor's pricing page, 2026-11-06):

- **1 Instagram account**: "1 user, 1 data source, 1 account" after the 30-day trial. The trial itself allows 10 sources and 15 accounts.
- **30 days of history**: you can't backfill further. PageLens keeps everything it has already stored, so your local history keeps growing from the first sync. Only posts older than 30 days stop getting new snapshots.
- **API access is not stated** on the pricing page. Test it during the trial: if a sync succeeds after the trial ends, the free plan works. More accounts or a longer window means Basic, at $19/month (annual) or $23/month, for 3 sources and 75 accounts.

**Compared with a direct Meta connection**, you lose:

- write actions
- stories data when Windsor doesn't return it
- profile views and website clicks (Meta deprecated these for everyone)

A third party also sees your Instagram data and holds its token.

**D. Business Suite CSV**

Go to Accounts → **Import CSV** and follow the steps in the dialog. It's free and works for any number of accounts, but it's manual. Import a fresh export whenever you want updated numbers. Each import adds a dated snapshot, so trends build up over time.

Sources: [Windsor.ai Instagram connector](https://windsor.ai/connectors/instagram/), [Windsor.ai Instagram → PostgreSQL](https://windsor.ai/connect/instagram-to-postgresql-integration/), [Make: Instagram for Business](https://www.make.com/en/help/app/instagram-business), [Make community: deprecated metrics](https://community.make.com/t/instagram-for-business-deprecated-metrics-when-using-get-media-insights/70713), [Metricool n8n connector (endpoint list)](https://socket.dev/npm/package/n8n-nodes-metricool-connector), [Coupler.io: exporting Instagram Insights](https://blog.coupler.io/how-to-export-data-from-instagram-insights/), [Instagram help: Download your information](https://help.instagram.com/ipad-app/181231772500920), [takeoutday.org export guide](https://takeoutday.org/guides/how-to-export-instagram-data).

## One app, many Instagram accounts?

**Yes, one app ID serves every account. But while the app stays in Standard Access (development mode, no App Review), only accounts that have a role on the app can connect.**

- The app ID and secret identify **PageLens**, not a person. Every account you connect goes through the same app, and PageLens stores a separate token for each account.
- The Facebook account that creates the app is just the app's **admin**. The Instagram accounts don't have to belong to it.
- **Instagram Login.** Any professional (Business or Creator) Instagram account can connect once the admin has invited it as an Instagram tester and the account has accepted. It needs no Facebook account or Page.
- **Facebook Login.** The Instagram account must be linked to a Facebook Page. The Facebook user who logs in must be able to administer that Page and must have a role on the app (admin, developer or tester).
- **Accounts with no role on the app**, such as a client's account you never invited, can't connect in development mode. Opening the app to arbitrary accounts needs Advanced Access: App Review, Business Verification, and switching the app to Live. PageLens is built for accounts you own or manage, so this isn't planned.

See also: [SETUP.md](SETUP.md) §4 for the setup steps, and [API_NOTES.md](API_NOTES.md) ("Access level") for Meta's rule on Standard Access.
