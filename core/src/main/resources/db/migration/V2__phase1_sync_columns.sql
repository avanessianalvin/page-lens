-- Phase 1: columns needed by the sync engine, based on the metrics verified in docs/API_NOTES.md (2026-11-05).

ALTER TABLE ig_account ADD COLUMN profile_picture_url TEXT;
ALTER TABLE ig_account ADD COLUMN follows_count BIGINT;
ALTER TABLE ig_account ADD COLUMN last_synced_at TIMESTAMPTZ;
-- Human-readable reason for the current status, e.g. why the account needs reconnecting.
ALTER TABLE ig_account ADD COLUMN status_message TEXT;

-- Counts from the media list call (available without insights; hidden counts stay NULL).
ALTER TABLE media ADD COLUMN like_count BIGINT;
ALTER TABLE media ADD COLUMN comments_count BIGINT;
ALTER TABLE media ADD COLUMN shortcode VARCHAR(64);
ALTER TABLE media ADD COLUMN media_url TEXT;
ALTER TABLE media ADD COLUMN last_insights_at TIMESTAMPTZ;
ALTER TABLE media ADD COLUMN last_comments_at TIMESTAMPTZ;

ALTER TABLE media_insight_snapshot ADD COLUMN total_interactions BIGINT;
ALTER TABLE media_insight_snapshot ADD COLUMN profile_visits BIGINT;
-- ig_reels_avg_watch_time, in milliseconds as returned by the API.
ALTER TABLE media_insight_snapshot ADD COLUMN avg_watch_time_ms BIGINT;

-- profile_views (V1) is no longer offered by the API and stays NULL. follower_delta is derived from
-- consecutive followers_count snapshots, because follower_count is not on the current reference page.
ALTER TABLE account_daily_metric ADD COLUMN accounts_engaged BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN total_interactions BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN likes BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN comments BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN shares BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN saves BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN replies BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN profile_links_taps BIGINT;
ALTER TABLE account_daily_metric ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Which metric a demographic row belongs to (follower_demographics today, engaged_audience_demographics later).
ALTER TABLE audience_snapshot ADD COLUMN metric VARCHAR(64) NOT NULL DEFAULT 'follower_demographics';
ALTER TABLE audience_snapshot DROP CONSTRAINT audience_snapshot_account_id_captured_at_dimension_dim_key_key;
ALTER TABLE audience_snapshot ADD CONSTRAINT uq_audience_snapshot
    UNIQUE (account_id, captured_at, metric, dimension, dim_key);

-- Shared rate-limit state: every PageLens process reads the latest usage from here before calling Meta.
ALTER TABLE api_call_log ADD COLUMN usage_percent INT;
ALTER TABLE api_call_log ADD COLUMN regain_access_at TIMESTAMPTZ;
CREATE INDEX idx_api_call_log_account_created ON api_call_log (account_id, created_at DESC);

CREATE INDEX idx_media_insight_snapshot_media ON media_insight_snapshot (media_id, captured_at DESC);
CREATE INDEX idx_sync_run_status ON sync_run (status, started_at DESC);
