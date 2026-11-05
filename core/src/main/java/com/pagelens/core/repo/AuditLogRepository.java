package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.pagelens.core.logging.CorrelationId;

/** audit_log plus the audit.log file: every state-changing action, by actor (ui, mcp, scheduler). */
@Repository
public class AuditLogRepository {

    public enum Actor { UI, MCP, SCHEDULER }

    public record AuditEntry(long id, String actor, String action, String target, String payloadSummary,
                             boolean dryRun, String outcome, String correlationId, Instant createdAt) {
    }

    private static final Logger audit = LoggerFactory.getLogger("pagelens.audit");

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code payloadSummary} must not contain tokens, secrets or comment text. */
    public void record(Actor actor, String action, String target, String payloadSummary, boolean dryRun,
                       String outcome) {
        String cid = CorrelationId.current();
        audit.atInfo().addKeyValue("actor", actor.name().toLowerCase()).addKeyValue("action", action)
                .addKeyValue("target", target).addKeyValue("dryRun", dryRun).addKeyValue("outcome", outcome)
                .log("{} {} {} -> {}", actor.name().toLowerCase(), action, target, outcome);
        jdbc.sql("""
                        INSERT INTO audit_log (actor, action, target, payload_summary, dry_run, outcome, correlation_id)
                        VALUES (:actor, :action, :target, :summary, :dry, :outcome, :cid)
                        """)
                .param("actor", actor.name().toLowerCase()).param("action", action).param("target", target)
                .param("summary", payloadSummary).param("dry", dryRun).param("outcome", outcome).param("cid", cid)
                .update();
    }

    public List<AuditEntry> recent(String actor, Instant before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1 = 1");
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = :actor");
        }
        if (before != null) {
            sql.append(" AND created_at < :before");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.min(Math.max(limit, 1), 1000));
        if (actor != null && !actor.isBlank()) {
            spec = spec.param("actor", actor.toLowerCase());
        }
        if (before != null) {
            spec = spec.param("before", ts(before));
        }
        return spec.query(AuditLogRepository::map).list();
    }

    private static AuditEntry map(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(rs.getLong("id"), rs.getString("actor"), rs.getString("action"), rs.getString("target"),
                rs.getString("payload_summary"), rs.getBoolean("dry_run"), rs.getString("outcome"),
                rs.getString("correlation_id"), instant(rs, "created_at"));
    }
}
