package com.pagelens.core.instagram;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence hooks the Graph client needs, implemented by the api_call_log repository.
 * Keeping it an interface lets the client be tested without a database.
 */
public interface ApiCallRecorder {

    /**
     * One row per HTTP attempt. {@code endpoint} is already secret-masked.
     */
    record ApiCall(Long accountId, String correlationId, String method, String endpoint, Integer httpStatus,
                   long durationMs, ApiUsage usage, String errorCode) {
    }

    void record(ApiCall call);

    /**
     * Latest usage seen by <em>any</em> PageLens process for this account, so the UI, scheduler and MCP
     * processes share one budget.
     */
    Optional<ApiUsage> latestUsage(Long accountId, Instant since);
}
