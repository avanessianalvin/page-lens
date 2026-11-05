package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.lng;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** media_insight_snapshot: one row per insights fetch, so a post's performance can be charted over time. */
@Repository
public class InsightRepository {

    /** All metric values nullable: NULL = not returned by the API. {@code extraMetrics} is JSON text. */
    public record Snapshot(Instant capturedAt, Long reach, Long views, Long likes, Long comments, Long shares,
                           Long saves, Long follows, Long totalInteractions, Long profileVisits, Long avgWatchTimeMs,
                           String extraMetrics) {
    }

    private final JdbcClient jdbc;

    public InsightRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long mediaId, Snapshot s) {
        jdbc.sql("""
                        INSERT INTO media_insight_snapshot (media_id, captured_at, reach, views, likes, comments, shares,
                            saves, follows, total_interactions, profile_visits, avg_watch_time_ms, extra_metrics)
                        VALUES (:media, :at, :reach, :views, :likes, :comments, :shares, :saves, :follows, :ti, :pv,
                                :watch, :extra)
                        ON CONFLICT (media_id, captured_at) DO NOTHING
                        """)
                .param("media", mediaId).param("at", ts(s.capturedAt())).param("reach", s.reach())
                .param("views", s.views()).param("likes", s.likes()).param("comments", s.comments())
                .param("shares", s.shares()).param("saves", s.saves()).param("follows", s.follows())
                .param("ti", s.totalInteractions()).param("pv", s.profileVisits()).param("watch", s.avgWatchTimeMs())
                .param("extra", s.extraMetrics())
                .update();
    }

    /** Oldest first, for charts. */
    public List<Snapshot> history(long mediaId) {
        return jdbc.sql("SELECT * FROM media_insight_snapshot WHERE media_id = :id ORDER BY captured_at")
                .param("id", mediaId).query(InsightRepository::map).list();
    }

    private static Snapshot map(ResultSet rs, int row) throws SQLException {
        return new Snapshot(instant(rs, "captured_at"), lng(rs, "reach"), lng(rs, "views"), lng(rs, "likes"),
                lng(rs, "comments"), lng(rs, "shares"), lng(rs, "saves"), lng(rs, "follows"),
                lng(rs, "total_interactions"), lng(rs, "profile_visits"), lng(rs, "avg_watch_time_ms"),
                rs.getString("extra_metrics"));
    }
}
