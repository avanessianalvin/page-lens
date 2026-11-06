package com.pagelens.web.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.alerts.AlertService;
import com.pagelens.core.alerts.AlertService.Alert;
import com.pagelens.core.analysis.AnalysisModels.Range;
import com.pagelens.core.analysis.AnalysisService;
import com.pagelens.core.auth.AccountPurgeService;
import com.pagelens.core.backup.BackupService;
import com.pagelens.core.config.BackupProperties;
import com.pagelens.core.config.PageLensProperties;
import com.pagelens.core.config.SyncProperties;
import com.pagelens.core.export.ExportService;
import com.pagelens.core.logging.CorrelationId;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.BackupRunRepository;
import com.pagelens.core.repo.BackupRunRepository.BackupRun;
import com.pagelens.core.sync.SyncService;
import com.pagelens.core.write.WriteGuard;

/**
 * Operations (Phase 4): alerts, settings, backups, export, purge and "sync all". Read-only towards Instagram
 * except sync-all, which starts normal syncs.
 */
@RestController
@RequestMapping("/api")
public class OpsController {

    public record Settings(String timeZone, boolean writeActionsEnabled, String syncCron, String storiesCron,
                           boolean backupEnabled, String backupCron, String backupDir, int backupKeepDays,
                           boolean pgDumpFound, String version) {
    }

    public record Backups(List<BackupRun> runs, List<BackupService.BackupFile> files, String dir) {
    }

    public record SyncAllResult(List<Map<String, Object>> started, List<Map<String, Object>> skipped) {
    }

    private final AlertService alerts;
    private final BackupService backups;
    private final BackupRunRepository backupRuns;
    private final BackupProperties backupProps;
    private final ExportService export;
    private final AccountPurgeService purge;
    private final AccountRepository accounts;
    private final AnalysisService analysis;
    private final SyncService sync;
    private final PageLensProperties props;
    private final SyncProperties syncProps;
    private final WriteGuard writes;
    private final TaskExecutor executor;

    public OpsController(AlertService alerts, BackupService backups, BackupRunRepository backupRuns,
                         BackupProperties backupProps, ExportService export, AccountPurgeService purge,
                         AccountRepository accounts, AnalysisService analysis, SyncService sync,
                         PageLensProperties props, SyncProperties syncProps, WriteGuard writes,
                         @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.alerts = alerts;
        this.backups = backups;
        this.backupRuns = backupRuns;
        this.backupProps = backupProps;
        this.export = export;
        this.purge = purge;
        this.accounts = accounts;
        this.analysis = analysis;
        this.sync = sync;
        this.props = props;
        this.syncProps = syncProps;
        this.writes = writes;
        this.executor = executor;
    }

    // ------------------------------------------------------------------------------------------ alerts & settings

    /** Current alerts, most severe first. {@code accountId} limits to one account (global alerts excluded). */
    @GetMapping("/alerts")
    public List<Alert> alerts(@RequestParam(required = false) Long accountId) {
        return accountId == null ? alerts.current() : alerts.forAccount(accountId);
    }

    /** Read-only settings (changed in .env, then restart; see docs/SETUP.md). */
    @GetMapping("/settings")
    public Settings settings() {
        String version = OpsController.class.getPackage().getImplementationVersion();
        return new Settings(props.zoneId().getId(), writes.enabled(), syncProps.cron(), syncProps.storiesCron(),
                backupProps.enabled(), backupProps.cron(), backups.dir().toString(), backupProps.keepDays(),
                backups.pgDumpFound(), version == null ? "dev" : version);
    }

    // ------------------------------------------------------------------------------------------ backups

    @GetMapping("/backups")
    public Backups backups() {
        return new Backups(backupRuns.recent(20), backups.list(), backups.dir().toString());
    }

    /** Starts a backup in the background; poll GET /api/backups for the result. */
    @PostMapping("/backups")
    public ResponseEntity<Map<String, String>> backupNow() {
        String cid = CorrelationId.current();
        executor.execute(() -> {
            try (var ignored = CorrelationId.use(cid == null ? "backup-ui" : cid)) {
                backups.run(Actor.UI);
            } catch (IllegalStateException alreadyRunning) {
                // reported by the running backup
            }
        });
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    // ------------------------------------------------------------------------------------------ export

    @GetMapping(value = "/export/posts.csv", produces = "text/csv")
    public ResponseEntity<byte[]> postsCsv(@RequestParam long accountId, @RequestParam(required = false) String period,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        Account a = account(accountId);
        return file(export.postsCsv(accountId, range(period, from, to)).getBytes(StandardCharsets.UTF_8),
                "text/csv;charset=UTF-8", fileName(a, "posts", "csv"));
    }

    @GetMapping("/export/posts.json")
    public ResponseEntity<List<Map<String, Object>>> postsJson(@RequestParam long accountId,
                                                               @RequestParam(required = false) String period,
                                                               @RequestParam(required = false) String from,
                                                               @RequestParam(required = false) String to) {
        Account a = account(accountId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName(a, "posts", "json"), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(export.posts(accountId, range(period, from, to)));
    }

    @GetMapping(value = "/export/daily.csv", produces = "text/csv")
    public ResponseEntity<byte[]> dailyCsv(@RequestParam long accountId, @RequestParam(required = false) String period,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        Account a = account(accountId);
        return file(export.dailyCsv(accountId, range(period, from, to)).getBytes(StandardCharsets.UTF_8),
                "text/csv;charset=UTF-8", fileName(a, "daily", "csv"));
    }

    // ------------------------------------------------------------------------------------------ accounts

    /** Deletes the account's synced data but keeps it connected. {@code confirm} must be the username. */
    @PostMapping("/accounts/{id}/purge")
    public Map<String, Integer> purge(@PathVariable long id, @RequestParam String confirm) {
        return purge.purge(id, confirm, Actor.UI);
    }

    /** Starts a FULL sync for every active account (accounts already syncing are skipped). */
    @PostMapping("/accounts/sync-all")
    public ResponseEntity<SyncAllResult> syncAll() {
        List<Map<String, Object>> started = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Account a : accounts.findActive()) {
            try {
                started.add(Map.of("accountId", a.id(), "runId",
                        sync.startAsync(a.id(), SyncService.JobType.FULL, SyncService.Trigger.UI)));
            } catch (SyncService.AlreadyRunningException e) {
                skipped.add(Map.of("accountId", a.id(), "reason", e.getMessage()));
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SyncAllResult(started, skipped));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Account account(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account " + id));
    }

    private Range range(String period, String from, String to) {
        return analysis.range(period, from, to, "all");
    }

    private static String fileName(Account a, String what, String ext) {
        return "pagelens-" + a.username().replaceAll("[^A-Za-z0-9._-]", "_") + "-" + what + "-" + LocalDate.now() + "." + ext;
    }

    private static ResponseEntity<byte[]> file(byte[] body, String contentType, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(contentType))
                .body(body);
    }
}
