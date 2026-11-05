package com.pagelens.core.instagram;

import java.time.Instant;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rate-limit usage parsed from Meta's {@code X-Business-Use-Case-Usage} / {@code X-App-Usage} headers.
 *
 * @param percent   highest of call_count / total_time / total_cputime across all entries (0-100+)
 * @param regainAt  when access returns, if {@code estimated_time_to_regain_access} was above zero
 * @param rawHeader the header value as received (contains no secrets), for api_call_log
 */
public record ApiUsage(int percent, Instant regainAt, String rawHeader) {

    public static Optional<ApiUsage> parse(JsonMapper json, String businessUseCaseHeader, String appUsageHeader,
                                           Instant now) {
        String raw = businessUseCaseHeader != null ? businessUseCaseHeader : appUsageHeader;
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = json.readTree(raw);
            int[] max = {0};
            long[] regainMinutes = {0};
            if (businessUseCaseHeader != null) {
                // {"<business-id>": [ {type, call_count, total_cputime, total_time, estimated_time_to_regain_access}, ... ]}
                for (var entry : root.properties()) {
                    JsonNode list = entry.getValue();
                    for (int i = 0; i < list.size(); i++) {
                        accumulate(list.get(i), max, regainMinutes);
                    }
                }
            } else {
                accumulate(root, max, regainMinutes);
            }
            Instant regainAt = regainMinutes[0] > 0 ? now.plusSeconds(regainMinutes[0] * 60) : null;
            return Optional.of(new ApiUsage(max[0], regainAt, raw));
        } catch (RuntimeException e) {
            return Optional.of(new ApiUsage(0, null, raw));
        }
    }

    private static void accumulate(JsonNode entry, int[] max, long[] regainMinutes) {
        for (String field : new String[] {"call_count", "total_time", "total_cputime"}) {
            max[0] = Math.max(max[0], entry.path(field).asInt(0));
        }
        regainMinutes[0] = Math.max(regainMinutes[0], entry.path("estimated_time_to_regain_access").asLong(0));
    }
}
