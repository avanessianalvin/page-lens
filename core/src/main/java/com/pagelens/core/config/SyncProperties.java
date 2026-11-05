package com.pagelens.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Sync engine tuning ({@code pagelens.sync.*}); see the comments in config/application.yml. */
@ConfigurationProperties(prefix = "pagelens.sync")
public record SyncProperties(boolean enabled, String cron, String storiesCron, String tokenCheckCron,
                             int recentDays, int olderRefreshDays, int olderRefreshBatch, int commentDays,
                             int dailyLookbackDays, int backfillDays, int mediaPageLimit,
                             int tokenWarningDays, int tokenRefreshAfterDays) {
}
