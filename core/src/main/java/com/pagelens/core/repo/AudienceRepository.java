package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.ts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AudienceRepository {

    /** Groups smaller than this are flagged as unreliable in the UI (plan section 12). */
    public static final int SMALL_SAMPLE = 30;

    public record AudienceEntry(String key, long value, boolean smallSample) {
    }

    public record AudienceBreakdown(String metric, String dimension, Instant capturedAt, List<AudienceEntry> entries) {
    }

    private final JdbcClient jdbc;

    public AudienceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long accountId, Instant capturedAt, String metric, String dimension, Map<String, Long> values) {
        for (var e : values.entrySet()) {
            jdbc.sql("""
                            INSERT INTO audience_snapshot (account_id, captured_at, metric, dimension, dim_key, value)
                            VALUES (:acc, :at, :metric, :dim, :key, :value)
                            ON CONFLICT (account_id, captured_at, metric, dimension, dim_key) DO NOTHING
                            """)
                    .param("acc", accountId).param("at", ts(capturedAt)).param("metric", metric)
                    .param("dim", dimension).param("key", e.getKey()).param("value", e.getValue())
                    .update();
        }
    }

    public Optional<Instant> latestCapture(long accountId, String metric) {
        return jdbc.sql("SELECT max(captured_at) FROM audience_snapshot WHERE account_id = :acc AND metric = :metric")
                .param("acc", accountId).param("metric", metric)
                .query((rs, n) -> instant(rs, "max")).optional(); // max() of no rows maps to null, which optional() turns into empty
    }

    /** The latest snapshot of one dimension, largest groups first. */
    public Optional<AudienceBreakdown> latest(long accountId, String metric, String dimension) {
        Optional<Instant> at = jdbc.sql("""
                        SELECT max(captured_at) FROM audience_snapshot
                        WHERE account_id = :acc AND metric = :metric AND dimension = :dim
                        """)
                .param("acc", accountId).param("metric", metric).param("dim", dimension)
                .query((rs, n) -> instant(rs, "max")).optional(); // max() of no rows maps to null, which optional() turns into empty
        return at.map(capturedAt -> new AudienceBreakdown(metric, dimension, capturedAt, jdbc.sql("""
                        SELECT dim_key, value FROM audience_snapshot
                        WHERE account_id = :acc AND metric = :metric AND dimension = :dim AND captured_at = :at
                        ORDER BY value DESC
                        """)
                .param("acc", accountId).param("metric", metric).param("dim", dimension).param("at", ts(capturedAt))
                .query((rs, n) -> new AudienceEntry(rs.getString("dim_key"), rs.getLong("value"),
                        rs.getLong("value") < SMALL_SAMPLE))
                .list()));
    }
}
