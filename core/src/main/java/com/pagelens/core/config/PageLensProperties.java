package com.pagelens.core.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings shared by every PageLens process, bound from the top-level {@code pagelens.*} keys.
 * Nested groups have their own records ({@link MetaProperties}, {@link HttpProperties}, {@link SyncProperties},
 * {@link MetricCatalog}).
 *
 * @param logDir              directory for app.log, api-calls.log and audit.log
 * @param timeZone            zone for display and best-time analysis; blank means the system default
 * @param writeActionsEnabled master switch for publish/reply/hide/delete (off by default)
 * @param encryptionKey       base64 AES-256 key for stored access tokens
 * @param uploadDir           staging folder for local video uploads
 */
@ConfigurationProperties(prefix = "pagelens")
public record PageLensProperties(String logDir, String timeZone, boolean writeActionsEnabled, String encryptionKey,
                                 String uploadDir) {

    /** The zone used for display and analysis. Storage is always UTC regardless of this value. */
    public ZoneId zoneId() {
        return (timeZone == null || timeZone.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(timeZone);
    }
}
