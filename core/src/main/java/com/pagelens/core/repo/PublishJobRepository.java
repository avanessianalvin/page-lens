package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.pagelens.core.write.PublishModels.JobStatus;
import com.pagelens.core.write.PublishModels.Kind;
import com.pagelens.core.write.PublishModels.PublishJob;

@Repository
public class PublishJobRepository {

    /** Raw row incl. JSON columns, for the publisher itself. */
    public record JobRow(PublishJob job, String itemsJson, String optionsJson, String childContainerIds) {
    }

    private static final String SELECT = """
            SELECT j.*, a.username FROM publish_job j JOIN ig_account a ON a.id = j.account_id
            """;

    private final JdbcClient jdbc;

    public PublishJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a QUEUED job, or returns the existing job's id if the idempotency key was used before. */
    public long insertOrGet(long accountId, String key, Kind kind, String caption, String itemsJson, int itemCount,
                            String optionsJson, String actor, String correlationId) {
        Optional<Long> inserted = jdbc.sql("""
                        INSERT INTO publish_job (account_id, idempotency_key, kind, caption, items_json, item_count,
                                                 options_json, actor, status, correlation_id)
                        VALUES (:acc, :key, :kind, :caption, :items, :count, :options, :actor, 'QUEUED', :cid)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("acc", accountId).param("key", key).param("kind", kind.name()).param("caption", caption)
                .param("items", itemsJson).param("count", itemCount).param("options", optionsJson).param("actor", actor)
                .param("cid", correlationId)
                .query(Long.class).optional();
        return inserted.orElseGet(() -> jdbc.sql("SELECT id FROM publish_job WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single());
    }

    public Optional<JobRow> find(long id) {
        return jdbc.sql(SELECT + " WHERE j.id = :id").param("id", id).query(PublishJobRepository::row).optional();
    }

    public Optional<PublishJob> findJob(long id) {
        return find(id).map(JobRow::job);
    }

    /** Atomically moves a job from one of {@code from} to {@code to}; false if another worker got there first. */
    public boolean claim(long id, List<JobStatus> from, JobStatus to) {
        String in = from.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
        return jdbc.sql("UPDATE publish_job SET status = :to, error_message = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = :id AND status IN (" + in + ")")
                .param("to", to.name()).param("id", id).update() == 1;
    }

    public void status(long id, JobStatus status) {
        jdbc.sql("UPDATE publish_job SET status = :s, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("s", status.name()).param("id", id).update();
    }

    public void children(long id, List<String> childIds) {
        jdbc.sql("UPDATE publish_job SET child_container_ids = :c, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("c", String.join(",", childIds)).param("id", id).update();
    }

    public void container(long id, String containerId) {
        jdbc.sql("UPDATE publish_job SET container_id = :c, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("c", containerId).param("id", id).update();
    }

    public void published(long id, String igMediaId, String permalink, Instant at) {
        jdbc.sql("""
                        UPDATE publish_job SET status = 'PUBLISHED', ig_media_id = :m, permalink = :p, published_at = :at,
                            error_message = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """)
                .param("m", igMediaId).param("p", permalink).param("at", ts(at)).param("id", id).update();
    }

    public void failed(long id, String message) {
        jdbc.sql("UPDATE publish_job SET status = 'FAILED', error_message = :e, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("e", message).param("id", id).update();
    }

    public List<PublishJob> recent(Long accountId, int limit) {
        var spec = jdbc.sql(SELECT + (accountId == null ? "" : " WHERE j.account_id = :acc")
                        + " ORDER BY j.created_at DESC, j.id DESC LIMIT :limit")
                .param("limit", Math.clamp(limit, 1, 200));
        if (accountId != null) {
            spec = spec.param("acc", accountId);
        }
        return spec.query(PublishJobRepository::row).list().stream().map(JobRow::job).toList();
    }

    /** Jobs left mid-flight (by a process that stopped) for longer than the cutoff. */
    public List<JobRow> stale(Instant updatedBefore) {
        return jdbc.sql(SELECT + " WHERE j.status IN ('CREATING','PROCESSING','PUBLISHING') AND j.updated_at < :before")
                .param("before", ts(updatedBefore)).query(PublishJobRepository::row).list();
    }

    private static JobRow row(ResultSet rs, int n) throws SQLException {
        String items = rs.getString("items_json");
        PublishJob job = new PublishJob(rs.getLong("id"), rs.getLong("account_id"), rs.getString("username"),
                rs.getString("idempotency_key"), Kind.valueOf(rs.getString("kind")), rs.getString("caption"),
                rs.getInt("item_count"),
                rs.getString("actor"), JobStatus.valueOf(rs.getString("status")), rs.getString("container_id"),
                rs.getString("ig_media_id"), rs.getString("permalink"), rs.getString("error_message"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "published_at"));
        return new JobRow(job, items, rs.getString("options_json"), rs.getString("child_container_ids"));
    }
}
