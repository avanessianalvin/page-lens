package com.pagelens.web.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.repo.ApiCallLogRepository;
import com.pagelens.core.repo.ApiCallLogRepository.ApiCallEntry;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.AuditEntry;
import com.pagelens.web.logs.AppLogReader;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final ApiCallLogRepository apiCalls;
    private final AuditLogRepository audit;
    private final AppLogReader appLog;

    public LogController(ApiCallLogRepository apiCalls, AuditLogRepository audit, AppLogReader appLog) {
        this.apiCalls = apiCalls;
        this.audit = audit;
        this.appLog = appLog;
    }

    /** Newest first. Page backwards with {@code before} = the last row's createdAt. */
    @GetMapping("/api-calls")
    public List<ApiCallEntry> apiCalls(@RequestParam(required = false) Long accountId,
                                       @RequestParam(defaultValue = "false") boolean errorsOnly,
                                       @RequestParam(required = false) String endpoint,
                                       @RequestParam(required = false) String correlationId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
                                       @RequestParam(defaultValue = "100") int limit) {
        return apiCalls.search(new ApiCallLogRepository.Filter(accountId, errorsOnly, endpoint, correlationId, before, limit));
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(required = false) String actor,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
                                  @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(actor, before, limit);
    }

    /** Tail of app.log (JSON lines), newest first, filtered. */
    @GetMapping("/app")
    public List<Map<String, Object>> app(@RequestParam(required = false) String level,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String correlationId,
                                         @RequestParam(defaultValue = "200") int limit) {
        return appLog.tail(level, q, correlationId, Math.min(Math.max(limit, 1), 2000));
    }
}
