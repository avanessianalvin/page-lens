package com.pagelens.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nightly backups ({@code pagelens.backup.*}).
 *
 * @param pgDump path to pg_dump; blank = auto-detect (PATH, then the newest PostgreSQL install on Windows)
 */
@ConfigurationProperties(prefix = "pagelens.backup")
public record BackupProperties(boolean enabled, String cron, String dir, int keepDays, String pgDump) {
}
