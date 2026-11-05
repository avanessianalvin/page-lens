# PageLens metric glossary

How to read the numbers PageLens returns. Source: Meta's Instagram Graph API v27.0 (verified 2026-11-05).

## General rules

- **Missing is not zero.** A metric absent from a result means Instagram did not return it: hidden like counts, a metric unsupported for that format, an account under 100 followers, a story with fewer than 5 viewers, or data not ready yet (up to 48 h delay). Never read it as 0.
- **Snapshots.** Post metrics are snapshots taken at each sync. The latest is the current lifetime total; the history shows how a post grew.
- **Time.** Timestamps are in the owner's time zone. Daily account metrics are UTC days.
- **Freshness.** Check `get_sync_status`. Recent posts refresh every sync (twice a day); older posts about weekly.

## Post metrics (lifetime)

| Metric | Meaning |
|---|---|
| reach | Unique accounts that saw the post (estimated by Meta). |
| views | Times the post was played or displayed on Instagram (organic). Replaces the deprecated "impressions". |
| likes, comments, shares, saves | Counts of each action. Saves = Meta's `saved`. |
| follows | Accounts that followed after seeing this post (feed posts and stories). |
| profile visits | Profile visits from this post. |
| interactions | Meta's `total_interactions` (likes + saves + comments + shares, minus undos); if missing, PageLens sums the known parts. |
| engagement rate | interactions / reach (0-1; 0.05 = 5 %). PageLens's definition, absent when reach is unknown or 0. |
| avg watch time | Reels only: average time a view lasted (`ig_reels_avg_watch_time`, milliseconds). |

## Account metrics (per UTC day)

| Metric | Meaning |
|---|---|
| reach | Unique accounts reached that day. **Summing days over-counts** people reached on several days. |
| views | Views of the account's content that day. |
| accounts engaged | Accounts that interacted with content that day. |
| followers | Follower count snapshot at the day's sync; the daily change is derived from consecutive snapshots. |
| follows_and_unfollows | Meta's daily follows (FOLLOWER) and unfollows (NON_FOLLOWER), when available (100+ followers). |

## Audience

Follower demographics for the last ~30 days (`follower_demographics`, timeframe `this_month`): country, city, age, gender. Only the top 45 groups are returned, and only for followers with demographic data, so totals are below the follower count. Groups under 30 people are flagged **smallSample**: treat them as noise.

## Formats

- **REELS**: Reels (video)
- **CAROUSEL**: multi-photo or multi-video posts
- **IMAGE**: single photo
- **VIDEO**: older feed video (pre-Reels)

Stories are analysed separately and are excluded from format and timing analysis.

## Small samples

Averages over fewer than 5 posts, posting-time slots with a single post, and audience groups under 30 are flagged `smallSample`. Say so when you rely on them.
