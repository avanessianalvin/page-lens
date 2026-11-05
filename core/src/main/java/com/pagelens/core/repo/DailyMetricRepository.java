package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.date;
import static com.pagelens.core.repo.Db.lng;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** account_daily_metric, keyed by (account, UTC day). Re-fetches only overwrite with values actually returned. */
@Repository
public class DailyMetricRepository {

    public record DailyMetric(LocalDate day, Long reach, Long views, Long accountsEngaged, Long totalInteractions,
                              Long likes, Long comments, Long shares, Long saves, Long replies, Long profileLinksTaps,
                              Long followersCount, Long followerDelta, String extraMetrics) {
    }

    private final JdbcClient jdbc;

    public DailyMetricRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Upserts API metric values for one day. Absent metrics keep their previous value (never become 0). */
    public void upsertMetrics(long accountId, LocalDate day, Map<String, Long> v, String extraJson) {
        jdbc.sql("""
                        INSERT INTO account_daily_metric (account_id, day, reach, views, accounts_engaged, total_interactions,
                            likes, comments, shares, saves, replies, profile_links_taps, extra_metrics)
                        VALUES (:acc, :day, :reach, :views, :engaged, :ti, :likes, :comments, :shares, :saves, :replies,
                                :taps, :extra)
                        ON CONFLICT (account_id, day) DO UPDATE SET
                            reach = COALESCE(EXCLUDED.reach, account_daily_metric.reach),
                            views = COALESCE(EXCLUDED.views, account_daily_metric.views),
                            accounts_engaged = COALESCE(EXCLUDED.accounts_engaged, account_daily_metric.accounts_engaged),
                            total_interactions = COALESCE(EXCLUDED.total_interactions, account_daily_metric.total_interactions),
                            likes = COALESCE(EXCLUDED.likes, account_daily_metric.likes),
                            comments = COALESCE(EXCLUDED.comments, account_daily_metric.comments),
                            shares = COALESCE(EXCLUDED.shares, account_daily_metric.shares),
                            saves = COALESCE(EXCLUDED.saves, account_daily_metric.saves),
                            replies = COALESCE(EXCLUDED.replies, account_daily_metric.replies),
                            profile_links_taps = COALESCE(EXCLUDED.profile_links_taps, account_daily_metric.profile_links_taps),
                            extra_metrics = COALESCE(EXCLUDED.extra_metrics, account_daily_metric.extra_metrics),
                            captured_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                        """)
                .param("acc", accountId).param("day", day).param("reach", v.get("reach")).param("views", v.get("views"))
                .param("engaged", v.get("accounts_engaged")).param("ti", v.get("total_interactions"))
                .param("likes", v.get("likes")).param("comments", v.get("comments")).param("shares", v.get("shares"))
                .param("saves", v.get("saves")).param("replies", v.get("replies"))
                .param("taps", v.get("profile_links_taps")).param("extra", extraJson)
                .update();
    }

    /**
     * Records the follower count seen today and derives {@code follower_delta} from the most recent earlier day
     * (plan's daily-snapshot fallback; Meta no longer documents follower_count).
     */
    public void recordFollowers(long accountId, LocalDate day, long followers) {
        Long previous = jdbc.sql("""
                        SELECT followers_count FROM account_daily_metric
                        WHERE account_id = :acc AND day < :day AND followers_count IS NOT NULL
                        ORDER BY day DESC LIMIT 1
                        """)
                .param("acc", accountId).param("day", day).query(Long.class).optional().orElse(null);
        Long delta = previous == null ? null : followers - previous;
        jdbc.sql("""
                        INSERT INTO account_daily_metric (account_id, day, followers_count, follower_delta)
                        VALUES (:acc, :day, :followers, :delta)
                        ON CONFLICT (account_id, day) DO UPDATE SET
                            followers_count = EXCLUDED.followers_count, follower_delta = EXCLUDED.follower_delta,
                            updated_at = CURRENT_TIMESTAMP
                        """)
                .param("acc", accountId).param("day", day).param("followers", followers).param("delta", delta)
                .update();
    }

    public boolean hasAny(long accountId) {
        return jdbc.sql("SELECT count(*) FROM account_daily_metric WHERE account_id = :acc AND reach IS NOT NULL")
                .param("acc", accountId).query(Long.class).single() > 0;
    }

    public List<DailyMetric> range(long accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT * FROM account_daily_metric WHERE account_id = :acc AND day BETWEEN :from AND :to
                        ORDER BY day
                        """)
                .param("acc", accountId).param("from", from).param("to", to)
                .query(DailyMetricRepository::map).list();
    }

    private static DailyMetric map(ResultSet rs, int row) throws SQLException {
        return new DailyMetric(date(rs, "day"), lng(rs, "reach"), lng(rs, "views"), lng(rs, "accounts_engaged"),
                lng(rs, "total_interactions"), lng(rs, "likes"), lng(rs, "comments"), lng(rs, "shares"),
                lng(rs, "saves"), lng(rs, "replies"), lng(rs, "profile_links_taps"), lng(rs, "followers_count"),
                lng(rs, "follower_delta"), rs.getString("extra_metrics"));
    }
}
