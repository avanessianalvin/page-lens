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

@Repository
public class SyncRunRepository {

    public enum Status { RUNNING, SUCCEEDED, PARTIAL, FAILED, SKIPPED }

    public record SyncRun(long id, Long accountId, String accountUsername, String jobType, String triggeredBy,
                          String correlationId, Instant startedAt, Instant finishedAt, String status,
                          int itemsFetched, String errorMessage) {
    }

    private final JdbcClient jdbc;

    public SyncRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A RUNNING row for this account started after {@code staleBefore} (older RUNNING rows count as crashed). */
    public boolean isRunning(long accountId, Instant staleBefore) {
        return jdbc.sql("""
                        SELECT count(*) FROM sync_run
                        WHERE account_id = :acc AND status = 'RUNNING' AND started_at > :stale
                        """)
                .param("acc", accountId).param("stale", ts(staleBefore)).query(Long.class).single() > 0;
    }

    public long start(long accountId, String jobType, String trigger, String correlationId) {
        return jdbc.sql("""
                        INSERT INTO sync_run (account_id, job_type, triggered_by, correlation_id, status)
                        VALUES (:acc, :job, :trigger, :cid, 'RUNNING') RETURNING id
                        """)
                .param("acc", accountId).param("job", jobType).param("trigger", trigger).param("cid", correlationId)
                .query(Long.class).single();
    }

    public void finish(long runId, Status status, int items, String error) {
        jdbc.sql("""
                        UPDATE sync_run SET status = :status, items_fetched = :items, error_message = :error,
                            finished_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """)
                .param("status", status.name()).param("items", items).param("error", error).param("id", runId)
                .update();
    }

    /** Marks RUNNING rows older than {@code staleBefore} as FAILED (process died mid-run). */
    public int failStale(Instant staleBefore) {
        return jdbc.sql("""
                        UPDATE sync_run SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP,
                            error_message = 'Abandoned (process stopped while running)'
                        WHERE status = 'RUNNING' AND started_at <= :stale
                        """)
                .param("stale", ts(staleBefore)).update();
    }

    public List<SyncRun> recent(Long accountId, int limit) {
        String where = accountId == null ? "" : " WHERE r.account_id = :acc";
        var spec = jdbc.sql("""
                SELECT r.*, a.username FROM sync_run r LEFT JOIN ig_account a ON a.id = r.account_id
                """ + where + " ORDER BY r.started_at DESC, r.id DESC LIMIT :limit")
                .param("limit", Math.min(Math.max(limit, 1), 500));
        if (accountId != null) {
            spec = spec.param("acc", accountId);
        }
        return spec.query(SyncRunRepository::map).list();
    }

    /** Finished runs of one job type for an account, newest first (RUNNING excluded). */
    public List<SyncRun> recentFinished(long accountId, String jobType, int limit) {
        return jdbc.sql("""
                        SELECT r.*, a.username FROM sync_run r LEFT JOIN ig_account a ON a.id = r.account_id
                        WHERE r.account_id = :acc AND r.job_type = :job AND r.status <> 'RUNNING'
                        ORDER BY r.started_at DESC, r.id DESC LIMIT :limit
                        """)
                .param("acc", accountId).param("job", jobType).param("limit", limit)
                .query(SyncRunRepository::map).list();
    }

    /** Newest successful (or partially successful) run of a job type for an account. */
    public java.util.Optional<SyncRun> lastSuccessful(long accountId, String jobType) {
        return jdbc.sql("""
                        SELECT r.*, a.username FROM sync_run r LEFT JOIN ig_account a ON a.id = r.account_id
                        WHERE r.account_id = :acc AND r.job_type = :job AND r.status IN ('SUCCEEDED', 'PARTIAL')
                        ORDER BY r.started_at DESC LIMIT 1
                        """)
                .param("acc", accountId).param("job", jobType).query(SyncRunRepository::map).optional();
    }

    public java.util.Optional<SyncRun> find(long runId) {
        return jdbc.sql("SELECT r.*, a.username FROM sync_run r LEFT JOIN ig_account a ON a.id = r.account_id WHERE r.id = :id")
                .param("id", runId).query(SyncRunRepository::map).optional();
    }

    private static SyncRun map(ResultSet rs, int row) throws SQLException {
        return new SyncRun(rs.getLong("id"), lng(rs, "account_id"), rs.getString("username"), rs.getString("job_type"),
                rs.getString("triggered_by"), rs.getString("correlation_id"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("status"), rs.getInt("items_fetched"),
                rs.getString("error_message"));
    }
}
