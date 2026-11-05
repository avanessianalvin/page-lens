package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.integer;
import static com.pagelens.core.repo.Db.lng;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.pagelens.core.instagram.ApiCallRecorder;
import com.pagelens.core.instagram.ApiUsage;

/** api_call_log: one row per Graph API attempt. Endpoints arrive already secret-masked. */
@Repository
public class ApiCallLogRepository implements ApiCallRecorder {

    public record ApiCallEntry(long id, Long accountId, String correlationId, String method, String endpoint,
                               Integer httpStatus, Long durationMs, Integer usagePercent, String rateUsageHeader,
                               String errorCode, Instant regainAccessAt, Instant createdAt) {
    }

    public record Filter(Long accountId, Boolean errorsOnly, String endpointContains, String correlationId,
                         Instant before, int limit) {
    }

    private final JdbcClient jdbc;

    public ApiCallLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(ApiCall c) {
        jdbc.sql("""
                        INSERT INTO api_call_log (account_id, correlation_id, http_method, endpoint, http_status, duration_ms,
                            rate_usage_header, usage_percent, regain_access_at, error_code)
                        VALUES (:acc, :cid, :method, :endpoint, :status, :ms, :header, :pct, :regain, :error)
                        """)
                .param("acc", c.accountId()).param("cid", c.correlationId()).param("method", c.method())
                .param("endpoint", c.endpoint()).param("status", c.httpStatus()).param("ms", c.durationMs())
                .param("header", c.usage() == null ? null : c.usage().rawHeader())
                .param("pct", c.usage() == null ? null : c.usage().percent())
                .param("regain", c.usage() == null ? null : ts(c.usage().regainAt()))
                .param("error", c.errorCode())
                .update();
    }

    @Override
    public Optional<ApiUsage> latestUsage(Long accountId, Instant since) {
        String acc = accountId == null ? "account_id IS NULL" : "account_id = :acc";
        var spec = jdbc.sql("""
                SELECT usage_percent, regain_access_at,
                       (SELECT max(regain_access_at) FROM api_call_log WHERE %s AND created_at > :since) AS max_regain
                FROM api_call_log WHERE %s AND created_at > :since AND usage_percent IS NOT NULL
                ORDER BY created_at DESC, id DESC LIMIT 1
                """.formatted(acc, acc)).param("since", ts(since));
        if (accountId != null) {
            spec = spec.param("acc", accountId);
        }
        return spec.query((rs, n) -> new ApiUsage(rs.getInt("usage_percent"), instant(rs, "max_regain"), null))
                .optional();
    }

    public List<ApiCallEntry> search(Filter f) {
        StringBuilder sql = new StringBuilder("SELECT * FROM api_call_log WHERE 1 = 1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (f.accountId() != null) {
            sql.append(" AND account_id = :acc");
            p.put("acc", f.accountId());
        }
        if (Boolean.TRUE.equals(f.errorsOnly())) {
            sql.append(" AND (error_code IS NOT NULL OR http_status >= 400)");
        }
        if (f.endpointContains() != null && !f.endpointContains().isBlank()) {
            sql.append(" AND endpoint LIKE :ep");
            p.put("ep", "%" + f.endpointContains() + "%");
        }
        if (f.correlationId() != null && !f.correlationId().isBlank()) {
            sql.append(" AND correlation_id = :cid");
            p.put("cid", f.correlationId());
        }
        if (f.before() != null) {
            sql.append(" AND created_at < :before");
            p.put("before", ts(f.before()));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        p.put("limit", Math.min(Math.max(f.limit(), 1), 1000));
        var spec = jdbc.sql(sql.toString());
        for (var e : p.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(ApiCallLogRepository::map).list();
    }

    private static ApiCallEntry map(ResultSet rs, int row) throws SQLException {
        return new ApiCallEntry(rs.getLong("id"), lng(rs, "account_id"), rs.getString("correlation_id"),
                rs.getString("http_method"), rs.getString("endpoint"), integer(rs, "http_status"),
                lng(rs, "duration_ms"), integer(rs, "usage_percent"), rs.getString("rate_usage_header"),
                rs.getString("error_code"), instant(rs, "regain_access_at"), instant(rs, "created_at"));
    }
}
