package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.lng;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.pagelens.core.instagram.IgModels.IgMedia;

@Repository
public class MediaRepository {

    /** Media row plus its latest insight snapshot (nullable). */
    public record Post(long id, long accountId, String igMediaId, String mediaType, String productType, String caption,
                       String permalink, String thumbnailUrl, String mediaUrl, Instant postedAt, boolean story,
                       Long likeCount, Long commentsCount, Instant lastInsightsAt, InsightRepository.Snapshot latest) {
    }

    /** What the sync engine needs to decide what to refresh. */
    public record SyncCandidate(long id, String igMediaId, String productType, boolean story, Instant postedAt,
                                Instant lastInsightsAt) {
    }

    public record PostQuery(long accountId, Instant from, Instant to, String type, String sort, boolean ascending,
                            int limit, int offset) {
    }

    public record Page<T>(List<T> items, long total) {
    }

    /** API sort keys → SQL expressions (whitelist, never interpolate user input). */
    private static final Map<String, String> SORTS = Map.of(
            "posted_at", "m.posted_at", "reach", "s.reach", "views", "s.views", "likes", "COALESCE(s.likes, m.like_count)",
            "comments", "COALESCE(s.comments, m.comments_count)", "shares", "s.shares", "saves", "s.saves",
            "follows", "s.follows", "total_interactions", "s.total_interactions");
    public static final Set<String> SORT_KEYS = SORTS.keySet();

    private static final String POST_SELECT = """
            SELECT m.id, m.account_id, m.ig_media_id, m.media_type, m.media_product_type, m.caption, m.permalink,
                   m.thumbnail_url, m.media_url, m.posted_at, m.is_story, m.like_count, m.comments_count,
                   m.last_insights_at,
                   s.id AS s_id, s.captured_at AS s_captured_at, s.reach AS s_reach, s.views AS s_views,
                   s.likes AS s_likes, s.comments AS s_comments, s.shares AS s_shares, s.saves AS s_saves,
                   s.follows AS s_follows, s.total_interactions AS s_total_interactions,
                   s.profile_visits AS s_profile_visits, s.avg_watch_time_ms AS s_avg_watch_time_ms,
                   s.extra_metrics AS s_extra_metrics
            FROM media m
            LEFT JOIN LATERAL (
                SELECT * FROM media_insight_snapshot x WHERE x.media_id = m.id ORDER BY x.captured_at DESC LIMIT 1
            ) s ON TRUE
            """;

    private final JdbcClient jdbc;

    public MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent upsert on the Instagram media id; returns the PageLens id. */
    public long upsert(long accountId, IgMedia m, boolean story, String rawJson) {
        return jdbc.sql("""
                        INSERT INTO media (account_id, ig_media_id, media_type, media_product_type, caption, permalink,
                                           thumbnail_url, media_url, posted_at, is_story, like_count, comments_count,
                                           shortcode, raw_json)
                        VALUES (:acc, :igId, :type, :product, :caption, :permalink, :thumb, :url, :posted, :story,
                                :likes, :comments, :shortcode, :raw)
                        ON CONFLICT (ig_media_id) DO UPDATE SET
                            media_type = EXCLUDED.media_type, media_product_type = EXCLUDED.media_product_type,
                            caption = COALESCE(EXCLUDED.caption, media.caption),
                            permalink = COALESCE(EXCLUDED.permalink, media.permalink),
                            thumbnail_url = COALESCE(EXCLUDED.thumbnail_url, media.thumbnail_url),
                            media_url = COALESCE(EXCLUDED.media_url, media.media_url),
                            posted_at = COALESCE(EXCLUDED.posted_at, media.posted_at),
                            like_count = EXCLUDED.like_count, comments_count = EXCLUDED.comments_count,
                            shortcode = COALESCE(EXCLUDED.shortcode, media.shortcode),
                            raw_json = EXCLUDED.raw_json, updated_at = CURRENT_TIMESTAMP
                        RETURNING id
                        """)
                .param("acc", accountId).param("igId", m.id()).param("type", m.mediaType() == null ? "UNKNOWN" : m.mediaType())
                .param("product", m.effectiveProductType()).param("caption", m.caption()).param("permalink", m.permalink())
                .param("thumb", m.thumbnailUrl()).param("url", m.mediaUrl()).param("posted", ts(m.timestamp()))
                .param("story", story).param("likes", m.likeCount()).param("comments", m.commentsCount())
                .param("shortcode", m.shortcode()).param("raw", rawJson)
                .query(Long.class).single();
    }

    public boolean exists(String igMediaId) {
        return jdbc.sql("SELECT count(*) FROM media WHERE ig_media_id = :id").param("id", igMediaId)
                .query(Long.class).single() > 0;
    }

    public long countForAccount(long accountId) {
        return jdbc.sql("SELECT count(*) FROM media WHERE account_id = :acc").param("acc", accountId)
                .query(Long.class).single();
    }

    /** Non-story posts newer than {@code since}: insights refreshed every run. */
    public List<SyncCandidate> recentPosts(long accountId, Instant since) {
        return jdbc.sql("""
                        SELECT id, ig_media_id, media_product_type, is_story, posted_at, last_insights_at FROM media
                        WHERE account_id = :acc AND is_story = FALSE AND posted_at >= :since
                        ORDER BY posted_at DESC
                        """)
                .param("acc", accountId).param("since", ts(since)).query(MediaRepository::candidate).list();
    }

    /** Older posts whose insights are stale, stalest first, capped at {@code limit}. */
    public List<SyncCandidate> staleOlderPosts(long accountId, Instant postedBefore, Instant insightsBefore, int limit) {
        return jdbc.sql("""
                        SELECT id, ig_media_id, media_product_type, is_story, posted_at, last_insights_at FROM media
                        WHERE account_id = :acc AND is_story = FALSE AND posted_at < :postedBefore
                          AND (last_insights_at IS NULL OR last_insights_at < :insightsBefore)
                        ORDER BY last_insights_at NULLS FIRST, posted_at DESC
                        LIMIT :limit
                        """)
                .param("acc", accountId).param("postedBefore", ts(postedBefore))
                .param("insightsBefore", ts(insightsBefore)).param("limit", limit)
                .query(MediaRepository::candidate).list();
    }

    public void markInsightsFetched(long mediaId, Instant at) {
        jdbc.sql("UPDATE media SET last_insights_at = :at WHERE id = :id").param("at", ts(at)).param("id", mediaId).update();
    }

    public void markCommentsFetched(long mediaId, Instant at) {
        jdbc.sql("UPDATE media SET last_comments_at = :at WHERE id = :id").param("at", ts(at)).param("id", mediaId).update();
    }

    public Page<Post> query(PostQuery q) {
        StringBuilder where = new StringBuilder(" WHERE m.account_id = :acc");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("acc", q.accountId());
        if (q.from() != null) {
            where.append(" AND m.posted_at >= :from");
            params.put("from", ts(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND m.posted_at < :to");
            params.put("to", ts(q.to()));
        }
        if (q.type() != null && !q.type().isBlank()) {
            // Matches either product type (FEED, REELS, STORY) or media type (IMAGE, VIDEO, CAROUSEL_ALBUM).
            where.append(" AND (m.media_product_type = :type OR m.media_type = :type)");
            params.put("type", q.type().toUpperCase());
        }
        if (!"STORY".equalsIgnoreCase(q.type())) {
            where.append(" AND m.is_story = FALSE");
        }
        String sortExpr = SORTS.getOrDefault(q.sort() == null ? "posted_at" : q.sort(), "m.posted_at");
        String order = " ORDER BY " + sortExpr + (q.ascending() ? " ASC" : " DESC") + " NULLS LAST, m.posted_at DESC";

        long total = bind(jdbc.sql("SELECT count(*) FROM media m" + where), params).query(Long.class).single();
        params.put("limit", Math.min(Math.max(q.limit(), 1), 500));
        params.put("offset", Math.max(q.offset(), 0));
        List<Post> items = bind(jdbc.sql(POST_SELECT + where + order + " LIMIT :limit OFFSET :offset"), params)
                .query(MediaRepository::post).list();
        return new Page<>(items, total);
    }

    /** All non-story posts (with latest snapshot) posted in [from, to), oldest first: input for analysis. */
    public List<Post> postsInRange(long accountId, Instant from, Instant to) {
        return jdbc.sql(POST_SELECT + """
                         WHERE m.account_id = :acc AND m.is_story = FALSE AND m.posted_at >= :from AND m.posted_at < :to
                         ORDER BY m.posted_at
                        """)
                .param("acc", accountId).param("from", ts(from)).param("to", ts(to))
                .query(MediaRepository::post).list();
    }

    public Optional<Post> findPost(long mediaId) {
        return jdbc.sql(POST_SELECT + " WHERE m.id = :id").param("id", mediaId).query(MediaRepository::post).optional();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (var e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec;
    }

    private static SyncCandidate candidate(ResultSet rs, int row) throws SQLException {
        return new SyncCandidate(rs.getLong("id"), rs.getString("ig_media_id"), rs.getString("media_product_type"),
                rs.getBoolean("is_story"), instant(rs, "posted_at"), instant(rs, "last_insights_at"));
    }

    private static Post post(ResultSet rs, int row) throws SQLException {
        InsightRepository.Snapshot latest = null;
        if (lng(rs, "s_id") != null) {
            latest = new InsightRepository.Snapshot(instant(rs, "s_captured_at"), lng(rs, "s_reach"), lng(rs, "s_views"),
                    lng(rs, "s_likes"), lng(rs, "s_comments"), lng(rs, "s_shares"), lng(rs, "s_saves"),
                    lng(rs, "s_follows"), lng(rs, "s_total_interactions"), lng(rs, "s_profile_visits"),
                    lng(rs, "s_avg_watch_time_ms"), rs.getString("s_extra_metrics"));
        }
        return new Post(rs.getLong("id"), rs.getLong("account_id"), rs.getString("ig_media_id"),
                rs.getString("media_type"), rs.getString("media_product_type"), rs.getString("caption"),
                rs.getString("permalink"), rs.getString("thumbnail_url"), rs.getString("media_url"),
                instant(rs, "posted_at"), rs.getBoolean("is_story"), lng(rs, "like_count"),
                lng(rs, "comments_count"), instant(rs, "last_insights_at"), latest);
    }
}
