# Instagram API Notes

Findings from Meta's official docs, recorded **before** writing the code that relies on them (plan section 14, rule 2).
Each entry has a verification date. Meta renames and deprecates metrics and permissions often, so re-verify anything older than a few months before using it.

## Verified 2026-11-05 (Phase 0)

Sources: [Instagram Platform overview](https://developers.facebook.com/docs/instagram-platform/overview), [Business Login for Instagram](https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login/business-login), [Insights](https://developers.facebook.com/docs/instagram-platform/insights).

### Two API setups (PageLens supports both, per account: `ig_account.login_type`)

- **Instagram Login.** Host `graph.instagram.com`. No Facebook Page needed.
  Scopes: `instagram_business_basic`, `instagram_business_manage_insights`, `instagram_business_manage_comments`, `instagram_business_content_publish`, `instagram_business_manage_messages`.
  The old `business_*` scope names were deprecated on 2025-01-27.
- **Facebook Login.** Host `graph.facebook.com`. Needs a linked Facebook Page, and the user must be able to administer it.
  Permissions: `instagram_basic`, `instagram_manage_insights`, `instagram_manage_comments`, `instagram_content_publish`, `pages_show_list`, `pages_read_engagement`.
- The Insights page says some fields (e.g. `total_comments`, `total_likes`, `total_views`) are **only available with Facebook Login**. Model metrics as nullable and per-setup.
- The overview mentions a newer **"Instagram API with Unified Login"** recommended for new integrations. It was evaluated in Phase 1 and not adopted (see below).

### Access level

- Standard Access is enough for accounts the owner owns or manages and that have a role on the app. No App Review is needed for our use.

### OAuth: Instagram Login

| Step | Endpoint |
|---|---|
| Authorize | `GET https://www.instagram.com/oauth/authorize?client_id&redirect_uri&response_type=code&scope=...` |
| Code → short-lived token | `POST https://api.instagram.com/oauth/access_token` |
| Short → long-lived (60 days) | `GET https://graph.instagram.com/access_token?grant_type=ig_exchange_token` |
| Refresh long-lived | `GET https://graph.instagram.com/refresh_access_token?grant_type=ig_refresh_token` |

- The authorization code is valid for 1 hour and works once. Strip a trailing `#_` from the code in the redirect.
- The short-lived token lasts 1 hour. Long-lived tokens last 60 days.
- Refresh only works when the token is ≥ 24 h old, not yet expired, and `instagram_business_basic` was granted. A token that isn't refreshed within 60 days can't be recovered, so the account must be reconnected (`status = NEEDS_RECONNECT`).
- Long-lived token calls include the app secret and **must be server-side**.

### Data retention / limits

- User (account) metrics are stored by Meta for up to **90 days**. Snapshot them daily or the history is lost.
- Some metrics are unavailable for accounts with **fewer than 100 followers**.

## Still to verify (before Phase 1 code)

## Verified 2026-11-05 (Phase 1)

Sources: Meta's machine-readable docs index (`developers.facebook.com/documentation/instagram-platform/llms.txt`) and the reference pages it links to: IG Media Insights, IG User Insights, IG User media/stories, IG Media comments, IG Media, IG User, Instagram Login get-started, Unified Login authentication, Facebook Login get-started, Facebook Login manual flow, Graph API rate limiting.

### Version

- **Pinned: `v27.0`**, the latest per the reference pages. It's configurable as `pagelens.meta.api-version`. Same version on both hosts.

### Login setups: decision

- **Instagram Login** and **Facebook Login** are both implemented, chosen per account.
- **Unified Login** is *not* implemented. It needs a business portfolio, Facebook Login for Business `config_id` configurations, and "Business Integration System User" tokens with a separate management API, and its docs are incomplete (no dialog URL, no refresh endpoint). Revisit if Meta deprecates the other two.

### Instagram Login

- `GET /me?fields=user_id,username`: **`user_id` is the IG professional account id** to use in `/<IG_ID>/media` and `/insights`. `id` is only app-scoped.
- Dashboard shortcut: *Instagram → API setup with Instagram business login → Generate token* yields a **60-day token** directly. PageLens accepts a pasted token as an alternative connect path (it avoids the redirect URI question below).
- The auth dialog accepts `state`, `enable_fb_login`, `force_reauth`. The code exchange (`POST api.instagram.com/oauth/access_token`) responds `{"data":[{"access_token","user_id","permissions"}]}`, and the response may also come unwrapped, so the client accepts both. Long-lived and refresh responses are `{access_token, token_type, expires_in}`.

### Facebook Login

- Dialog `https://www.facebook.com/v27.0/dialog/oauth?client_id&redirect_uri&state&scope&response_type=code`.
- Code exchange `GET graph.facebook.com/v27.0/oauth/access_token?client_id&redirect_uri&client_secret&code` → `{access_token, token_type, expires_in}`.
- Long-lived: `grant_type=fb_exchange_token&client_id&client_secret&fb_exchange_token` (the standard Graph flow; not on the IG pages, verify on first live connect). About 60 days, and it can't be refreshed silently: the user reconnects.
- IG account lookup: `GET /me/accounts` (Pages the user can MANAGE/CREATE_CONTENT/MODERATE/ADVERTISE) → `GET /{page-id}?fields=instagram_business_account` → `instagram_business_account.id` is the IG user id. PageLens requests both in one call: `/me/accounts?fields=id,name,instagram_business_account{id,username}`.
- Scopes requested: `instagram_basic, instagram_manage_insights, instagram_manage_comments, pages_show_list, pages_read_engagement`.

### Media fields (`/<IG_ID>/media`, max 10K most recent; stories excluded)

- Requested: `id,caption,media_type,media_product_type,permalink,thumbnail_url,media_url,timestamp,like_count,comments_count,shortcode`.
- `media_type` ∈ `IMAGE`, `VIDEO`, `CAROUSEL_ALBUM`. `media_product_type` ∈ `AD`, `FEED`, `STORY`, `REELS`, but the reference marks it (and oddly `caption`) **"Facebook Login only"**. PageLens requests them anyway. If the field request fails it retries without the flagged fields, and infers product type (`VIDEO` → `REELS`, else `FEED`) when it's missing. **Verify on first Instagram Login sync.**
- `timestamp` is ISO 8601 in the form `2026-11-01T10:00:00+0000` (no colon in the offset).
- `like_count` and other counts are omitted if the owner hid them. `media_url` can be omitted (copyright). Treat all as optional.

### Media insights (`/<IG_MEDIA_ID>/insights`, period is always `lifetime`)

| Product type | Metrics PageLens requests |
|---|---|
| FEED (image, carousel) | `reach, views, likes, comments, shares, saved, total_interactions, follows, profile_visits` |
| REELS | `reach, views, likes, comments, shares, saved, total_interactions, ig_reels_avg_watch_time, ig_reels_video_view_total_time` |
| STORY | `reach, views, shares, total_interactions, follows, profile_visits, replies` |

- **`impressions` is deprecated** (media after 2024-07-02; account-level removed 2025-04-21). Don't use it.
- `total_comments`, `total_likes`, `total_views` are Facebook Login only (optional, configured separately).
- `views`, `total_interactions`, `ig_reels_video_view_total_time` are marked "in development", so expect occasional errors. A failing multi-metric request is retried **per metric**; failing metrics are logged as unsupported, never stored as 0.
- Missing data comes back as an empty set, not 0. Data can lag up to **48 h**. Metrics are kept **2 years**. There are **no insights for album children**.
- Stories: metrics are available for **24 h** only. Fewer than 5 viewers → error `(#10) Not enough viewers`. The `story_insights` webhook is Facebook Login only.
- Requesting a breakdown on a metric that doesn't support it returns the generic `An unknown error has occurred.`

### Account insights (`/<IG_ID>/insights`)

- Daily, `period=day&metric_type=total_value&since&until` (Unix, inclusive; default is the last 24 h): `reach, views, accounts_engaged, total_interactions, likes, comments, shares, saves, replies, profile_links_taps`.
- `follows_and_unfollows` (needs `breakdown=follow_type`, and not returned under 100 followers). It's stored in `extra_metrics`.
- **`follower_count` and `profile_views` are not on the current reference page.** PageLens snapshots `followers_count` from the profile on every sync and derives `follower_delta` from consecutive days (the plan's "daily-snapshot fallback"). `profile_views` stays NULL.
- Demographics: `follower_demographics`, `period=lifetime&metric_type=total_value&timeframe=this_month&breakdown=<age|city|country|gender>`. Top **45** results only. Not returned under **100 followers**. Values only count users with demographic data, so they won't sum to the follower count. `last_14_days`, `last_30_days`, `last_90_days` and `prev_month` are unsupported from v20.0.
- Response: `data[].total_value.value`, with breakdowns in `total_value.breakdowns[].results[].{dimension_values[], value}`.
- Meta's docs don't say which time zone the API's day boundaries use. PageLens queries UTC days.

### Comments (`/<IG_MEDIA_ID>/comments`)

- Top-level only, reverse chronological, **at most 50 per query**. Replies come via field expansion `replies{...}`. There's no timestamp filtering.
- The IG Comment reference page returns 404. PageLens requests `id,text,timestamp,username,like_count,hidden,parent_id` and falls back to `id,text,timestamp,username` if that's rejected. **Verify on first sync.**

### Stories (`/<IG_ID>/stories`)

- Documented for Facebook Login only. Instagram Login support is unconfirmed: PageLens tries, and on an error disables story polling for that account in memory and logs it once.

### Rate limits

- Instagram budget: **4800 × impressions calls / 24 h**, per app + app-user, rolling.
- Header `X-Business-Use-Case-Usage`: `{"<business-id>":[{"type":"instagram","call_count":n,"total_cputime":n,"total_time":n,"estimated_time_to_regain_access":minutes}]}`. Header `X-App-Usage`: `{"call_count","total_time","total_cputime"}`. Values are percentages.
- Throttle error codes: `80002` (Instagram BUC), `4`, `17`, `32`, `613`. Failed calls still count, so stop until the time in `estimated_time_to_regain_access`.

## Still to verify (needs a live account)

- [ ] Whether Instagram Login and Facebook Login accept `http://localhost:8080/auth/callback`. Fallback: paste a dashboard-generated token.
- [ ] `media_product_type` and `caption` availability with Instagram Login.
- [ ] IG Comment fields `like_count`, `hidden`, `parent_id`.
- [ ] `/stories` with Instagram Login.
- [ ] Day boundary time zone of account insights.
- [ ] Replace hand-written test fixtures with recorded real responses (tokens and personal data scrubbed).
- [ ] Webhooks as an alternative to polling for comments and stories (optional, later).
