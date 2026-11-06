package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.lng;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BackupRunRepository {

    public record BackupRun(long id, String triggeredBy, Instant startedAt, Instant finishedAt, String status,
                            String fileName, Long sizeBytes, String errorMessage) {
    }

    private final JdbcClient jdbc;

    public BackupRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String triggeredBy) {
        return jdbc.sql("INSERT INTO backup_run (triggered_by, status) VALUES (:t, 'RUNNING') RETURNING id")
                .param("t", triggeredBy).query(Long.class).single();
    }

    public void succeeded(long id, String fileName, long size) {
        jdbc.sql("""
                        UPDATE backup_run SET status = 'SUCCEEDED', file_name = :f, size_bytes = :s,
                            finished_at = CURRENT_TIMESTAMP WHERE id = :id
                        """)
                .param("f", fileName).param("s", size).param("id", id).update();
    }

    public void failed(long id, String error) {
        jdbc.sql("UPDATE backup_run SET status = 'FAILED', error_message = :e, finished_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("e", error).param("id", id).update();
    }

    public List<BackupRun> recent(int limit) {
        return jdbc.sql("SELECT * FROM backup_run ORDER BY started_at DESC, id DESC LIMIT :l")
                .param("l", Math.clamp(limit, 1, 200)).query(BackupRunRepository::map).list();
    }

    public Optional<BackupRun> lastSuccessful() {
        return jdbc.sql("SELECT * FROM backup_run WHERE status = 'SUCCEEDED' ORDER BY started_at DESC LIMIT 1")
                .query(BackupRunRepository::map).optional();
    }

    private static BackupRun map(ResultSet rs, int n) throws SQLException {
        return new BackupRun(rs.getLong("id"), rs.getString("triggered_by"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("status"), rs.getString("file_name"), lng(rs, "size_bytes"),
                rs.getString("error_message"));
    }
}
