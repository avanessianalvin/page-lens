package com.pagelens.core.backup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pagelens.core.logging.CorrelationId;
import com.pagelens.core.repo.AuditLogRepository.Actor;

/** Nightly backup; fires only in a process with scheduling enabled (app-web). */
@Component
@ConditionalOnProperty(name = "pagelens.backup.enabled", havingValue = "true", matchIfMissing = true)
public class BackupScheduler {

    private final BackupService backups;

    public BackupScheduler(BackupService backups) {
        this.backups = backups;
    }

    @Scheduled(cron = "${pagelens.backup.cron}")
    public void nightly() {
        try (var ignored = CorrelationId.open("backup")) {
            backups.run(Actor.SCHEDULER);
        } catch (IllegalStateException alreadyRunning) {
            // a manual backup is in progress; tonight's is covered
        }
    }
}
