package com.pagelens.core.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.pagelens.core.auth.TokenService;
import com.pagelens.core.auth.TokenService.TokenHealth;
import com.pagelens.core.config.BackupProperties;
import com.pagelens.core.instagram.RateLimitGuard;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.BackupRunRepository;
import com.pagelens.core.repo.PublishJobRepository;
import com.pagelens.core.repo.SyncRunRepository;
import com.pagelens.core.repo.SyncRunRepository.SyncRun;
import com.pagelens.core.write.PublishModels.JobStatus;

/**
 * Things the owner should act on (plan section 11: "a simple alert in the UI when a sync fails twice in a row"),
 * computed on demand from local state. Read-only; never calls Instagram.
 */
@Service
public class AlertService {

    public enum Severity { CRITICAL, WARNING, INFO }

    /**
     * @param action what to do about it, in a few words
     */
    public record Alert(Severity severity, String kind, Long accountId, String account, String message, String action,
                        Instant since) {
    }

    static final Duration STALE_DATA = Duration.ofHours(48);
    static final Duration STALE_BACKUP = Duration.ofHours(48);
    static final Duration RECENT_PUBLISH_FAILURE = Duration.ofHours(24);

    private final AccountRepository accounts;
    private final TokenService tokens;
    private final SyncRunRepository runs;
    private final BackupRunRepository backups;
    private final BackupProperties backupProps;
    private final PublishJobRepository publishJobs;
    private final RateLimitGuard rateLimit;
    private final Clock clock;

    public AlertService(AccountRepository accounts, TokenService tokens, SyncRunRepository runs,
                        BackupRunRepository backups, BackupProperties backupProps, PublishJobRepository publishJobs,
                        RateLimitGuard rateLimit, Clock clock) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.runs = runs;
        this.backups = backups;
        this.backupProps = backupProps;
        this.publishJobs = publishJobs;
        this.rateLimit = rateLimit;
        this.clock = clock;
    }

    /** All current alerts, most severe first. */
    public List<Alert> current() {
        Instant now = clock.instant();
        List<Alert> out = new ArrayList<>();
        List<Account> all = accounts.findAll();
        for (Account a : all) {
            accountAlerts(a, now, out);
        }
        backupAlerts(now, !all.isEmpty(), out);
        out.sort(Comparator.comparing(Alert::severity).thenComparing(Alert::since, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    public List<Alert> forAccount(long accountId) {
        return current().stream().filter(x -> x.accountId() != null && x.accountId() == accountId).toList();
    }

    private void accountAlerts(Account a, Instant now, List<Alert> out) {
        String name = "@" + a.username();
        if (!"ACTIVE".equals(a.status())) {
            out.add(new Alert(Severity.CRITICAL, "needs_reconnect", a.id(), name,
                    a.statusMessage() != null ? a.statusMessage() : "Instagram rejected the access token.",
                    "Reconnect the account", null));
        }
        TokenHealth t = tokens.health(a.id());
        if ("INVALID".equals(t.status()) && "ACTIVE".equals(a.status())) {
            out.add(new Alert(Severity.CRITICAL, "token_invalid", a.id(), name, "The stored token is invalid.",
                    "Reconnect the account", null));
        } else if (t.expiringSoon() && !"INVALID".equals(t.status())) {
            out.add(new Alert(Severity.WARNING, "token_expiring", a.id(), name,
                    "The access token expires in " + t.daysLeft() + " day(s).",
                    a.loginType() == com.pagelens.core.instagram.LoginType.FACEBOOK_LOGIN
                            ? "Reconnect with Facebook to renew it" : "Reconnect if automatic refresh keeps failing",
                    t.expiresAt()));
        }

        List<SyncRun> last = runs.recentFinished(a.id(), "FULL", 2);
        if (last.size() == 2 && last.stream().allMatch(r -> "FAILED".equals(r.status()))) {
            out.add(new Alert(Severity.CRITICAL, "sync_failed_twice", a.id(), name,
                    "The last two syncs failed: " + firstLine(last.getFirst().errorMessage()),
                    "Check Logs → Sync runs", last.getFirst().finishedAt()));
        }
        if ("ACTIVE".equals(a.status()) && a.createdAt() != null && a.createdAt().isBefore(now.minus(Duration.ofDays(1)))) {
            Instant lastOk = runs.lastSuccessful(a.id(), "FULL").map(SyncRun::finishedAt).orElse(null);
            if (lastOk == null || lastOk.isBefore(now.minus(STALE_DATA))) {
                out.add(new Alert(Severity.WARNING, "data_stale", a.id(), name,
                        lastOk == null ? "No successful sync yet." : "No successful sync for over 2 days.",
                        "Make sure app-web is running, or use Sync now", lastOk));
            }
        }
        rateLimit.current(a.id()).filter(u -> u.regainAt() != null && u.regainAt().isAfter(now)).ifPresent(u ->
                out.add(new Alert(Severity.INFO, "rate_limited", a.id(), name,
                        "Instagram rate limit reached; calls are paused until " + u.regainAt() + ".",
                        "Wait; syncs resume automatically", now)));
        publishJobs.recent(a.id(), 20).stream()
                .filter(j -> j.status() == JobStatus.FAILED && j.updatedAt() != null
                        && j.updatedAt().isAfter(now.minus(RECENT_PUBLISH_FAILURE)))
                .findFirst()
                .ifPresent(j -> out.add(new Alert(Severity.WARNING, "publish_failed", a.id(), name,
                        "Publishing job " + j.id() + " failed: " + firstLine(j.errorMessage()),
                        "Open Publish to retry", j.updatedAt())));
    }

    private void backupAlerts(Instant now, boolean hasData, List<Alert> out) {
        if (!backupProps.enabled()) {
            if (hasData) {
                out.add(new Alert(Severity.INFO, "backups_disabled", null, null, "Nightly backups are turned off.",
                        "Set PAGELENS_BACKUP_ENABLED=true", null));
            }
            return;
        }
        var recent = backups.recent(1);
        if (!recent.isEmpty() && "FAILED".equals(recent.getFirst().status())) {
            out.add(new Alert(Severity.WARNING, "backup_failed", null, null,
                    "The last backup failed: " + firstLine(recent.getFirst().errorMessage()),
                    "See Settings → Backups", recent.getFirst().finishedAt()));
            return;
        }
        Instant lastOk = backups.lastSuccessful().map(BackupRunRepository.BackupRun::finishedAt).orElse(null);
        if (hasData && (lastOk == null || lastOk.isBefore(now.minus(STALE_BACKUP)))) {
            out.add(new Alert(Severity.WARNING, "backup_stale", null, null,
                    lastOk == null ? "No backup has been made yet." : "The last backup is more than 2 days old.",
                    "Run a backup in Settings", lastOk));
        }
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "(no details)";
        }
        String line = s.lines().findFirst().orElse(s);
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
