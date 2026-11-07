package com.pagelens.core.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pagelens.core.auth.TokenService;
import com.pagelens.core.logging.CorrelationId;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.sync.SyncService.JobType;
import com.pagelens.core.sync.SyncService.Trigger;

/**
 * Scheduled jobs. They only fire in a process that enables scheduling ({@code @EnableScheduling}), which is
 * app-web only, so the MCP process never syncs on its own. Disable with {@code pagelens.sync.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "pagelens.sync.enabled", havingValue = "true", matchIfMissing = true)
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService sync;
    private final TokenService tokens;
    private final AccountRepository accounts;
    private final com.pagelens.core.write.PublishService publisher;

    public SyncScheduler(SyncService sync, TokenService tokens, AccountRepository accounts,
                         com.pagelens.core.write.PublishService publisher) {
        this.sync = sync;
        this.tokens = tokens;
        this.accounts = accounts;
        this.publisher = publisher;
    }

    @Scheduled(cron = "${pagelens.sync.cron}")
    public void syncAll() {
        runForActiveAccounts(JobType.FULL);
    }

    /** Stories expire after 24 h and take their insights with them, so poll every few hours. */
    @Scheduled(cron = "${pagelens.sync.stories-cron}")
    public void syncStories() {
        runForActiveAccounts(JobType.STORIES);
    }

    @Scheduled(cron = "${pagelens.sync.token-check-cron}")
    public void checkTokens() {
        try (var ignored = CorrelationId.open("tokens")) {
            tokens.checkAll();
        }
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void cleanUpStaleRuns() {
        int n = sync.failStaleRuns();
        if (n > 0) {
            log.warn("Marked {} abandoned sync run(s) as FAILED", n);
        }
        int p = publisher.recoverStale();
        if (p > 0) {
            log.warn("Resolved {} interrupted publish job(s); see the Publish page", p);
        }
    }

    private void runForActiveAccounts(JobType job) {
        for (Account a : accounts.findActive()) {
            if (a.loginType() == com.pagelens.core.instagram.LoginType.IMPORT) {
                continue; // CSV-only account: nothing to fetch
            }
            try {
                sync.run(a.id(), job, Trigger.SCHEDULER);
            } catch (SyncService.AlreadyRunningException e) {
                log.info("Skipping scheduled {} for @{}: {}", job, a.username(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Scheduled {} for @{} failed", job, a.username(), e);
            }
        }
    }
}
