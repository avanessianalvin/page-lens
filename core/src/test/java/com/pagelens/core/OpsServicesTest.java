package com.pagelens.core;

import static com.pagelens.testsupport.FakeGraphApi.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pagelens.core.alerts.AlertService;
import com.pagelens.core.alerts.AlertService.Alert;
import com.pagelens.core.alerts.AlertService.Severity;
import com.pagelens.core.analysis.AnalysisService;
import com.pagelens.core.auth.AccountConnectionService;
import com.pagelens.core.auth.AccountPurgeService;
import com.pagelens.core.backup.BackupService;
import com.pagelens.core.export.ExportService;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.sync.SyncService;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** Phase 4 services against PostgreSQL: alerts, purge, export, backups. */
@SpringBootTest(properties = "pagelens.backup.dir=target/test-backups")
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class OpsServicesTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final String IG = "17841400000000001";
    static final DateTimeFormatter IG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired
    AlertService alerts;
    @Autowired
    AccountPurgeService purge;
    @Autowired
    ExportService export;
    @Autowired
    BackupService backups;
    @Autowired
    AnalysisService analysis;
    @Autowired
    AccountConnectionService connections;
    @Autowired
    SyncService sync;
    @Autowired
    JdbcClient jdbc;

    long accountId;

    @BeforeEach
    void setUp() {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        jdbc.sql("DELETE FROM sync_run").update();
        jdbc.sql("DELETE FROM backup_run").update();
        jdbc.sql("DELETE FROM audit_log").update();
        stub();
        accountId = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        sync.run(accountId, SyncService.JobType.FULL, SyncService.Trigger.SCHEDULER);
    }

    @Test
    void healthyAccountHasOnlyTheNoBackupYetAlert() {
        assertThat(alerts.current()).extracting(Alert::kind).containsExactly("backup_stale");
    }

    @Test
    void twoFailedSyncsInARowRaiseACriticalAlert() {
        jdbc.sql("""
                INSERT INTO sync_run (account_id, job_type, triggered_by, status, started_at, finished_at, error_message)
                VALUES (:a, 'FULL', 'scheduler', 'FAILED', now() + interval '1 minute', now() + interval '2 minutes', 'media: boom'),
                       (:a, 'FULL', 'scheduler', 'FAILED', now() + interval '3 minutes', now() + interval '4 minutes', 'profile: Network error')
                """).param("a", accountId).update();

        assertThat(alerts.forAccount(accountId)).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo("sync_failed_twice");
            assertThat(a.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(a.message()).contains("profile: Network error");
        });
        // A single failure after a success is not an alert.
        jdbc.sql("DELETE FROM sync_run WHERE error_message = 'media: boom'").update();
        assertThat(alerts.forAccount(accountId)).noneMatch(a -> a.kind().equals("sync_failed_twice"));
    }

    @Test
    void reconnectExpiringTokenStaleDataAndFailedPublishAreFlagged() {
        jdbc.sql("UPDATE ig_token SET expires_at = now() + interval '3 days'").update();
        jdbc.sql("UPDATE ig_account SET created_at = now() - interval '10 days'").update();
        jdbc.sql("UPDATE sync_run SET started_at = now() - interval '5 days', finished_at = now() - interval '5 days'").update();
        jdbc.sql("""
                INSERT INTO publish_job (account_id, idempotency_key, kind, items_json, item_count, actor, status, error_message)
                VALUES (:a, 'k', 'IMAGE', '[]', 1, 'ui', 'FAILED', 'Instagram could not process the media')
                """).param("a", accountId).update();

        assertThat(alerts.forAccount(accountId)).extracting(Alert::kind)
                .contains("token_expiring", "data_stale", "publish_failed");

        jdbc.sql("UPDATE ig_account SET status = 'NEEDS_RECONNECT', status_message = 'Token expired'").update();
        assertThat(alerts.current().getFirst().kind()).isEqualTo("needs_reconnect"); // critical first
    }

    @Test
    void purgeNeedsTheUsernameAndKeepsTheAccountConnected() {
        assertThatThrownBy(() -> purge.purge(accountId, "wrong", Actor.UI)).hasMessageContaining("pagelens.test");

        var deleted = purge.purge(accountId, "@pagelens.test", Actor.UI);

        assertThat(deleted).containsEntry("posts", 3).containsEntry("comments", 3);
        assertThat(count("media")).isZero();
        assertThat(count("account_daily_metric")).isZero();
        assertThat(count("audience_snapshot")).isZero();
        assertThat(count("ig_account")).isEqualTo(1);
        assertThat(count("ig_token")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE action = 'account.purge'").query(String.class).list()).hasSize(1);
    }

    @Test
    void exportsPostsAndDailyMetrics() {
        String posts = export.postsCsv(accountId, analysis.range("all", null, null));
        assertThat(posts).startsWith("\uFEFFpost_id,instagram_id,posted_at,format");
        assertThat(posts.lines()).hasSize(4); // header + 3 posts
        assertThat(posts).contains("سلام دوستان").contains("REELS").contains(",900,"); // the Reel's reach

        var json = export.posts(accountId, analysis.range("all", null, null));
        assertThat(json.getFirst()).containsEntry("format", "REELS"); // newest first
        assertThat(json.get(2).get("views")).isNull(); // not returned for that post: empty, never 0
        assertThat(posts).contains(",,"); // ...and an empty CSV cell

        String daily = export.dailyCsv(accountId, analysis.range("all", null, null));
        assertThat(daily.lines().skip(1)).allSatisfy(l -> assertThat(l).matches("^\\d{4}-\\d{2}-\\d{2},.*"));
        assertThat(daily).contains(",1250,"); // today's follower snapshot
    }

    @Test
    void backupWithRealPgDumpProducesARestorableArchive() throws Exception {
        String pgDump = pgDumpPath();
        assumeTrue(pgDump != null, "pg_dump not installed; skipping the real backup test");

        var run = backups.run(Actor.UI);

        assertThat(run.status()).as(String.valueOf(run.errorMessage())).isEqualTo("SUCCEEDED");
        Path file = backups.dir().resolve(run.fileName());
        assertThat(file).exists();
        assertThat(Files.size(file)).isGreaterThan(1000);
        Process list = new ProcessBuilder(pgDump.replace("pg_dump", "pg_restore"), "--list", file.toString())
                .redirectErrorStream(true).start();
        String toc = new String(list.getInputStream().readAllBytes());
        assertThat(list.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(toc).contains("TABLE pagelens media").contains("TABLE pagelens ig_token")
                .contains("TABLE DATA pagelens media");
        assertThat(backups.list()).extracting(BackupService.BackupFile::name).contains(run.fileName());
        // A successful backup clears the "no backup yet" alert.
        assertThat(alerts.current()).noneMatch(a -> a.kind().startsWith("backup_"));

        // The documented restore command (docs/RUNBOOK.md) brings the data back.
        jdbc.sql("DELETE FROM ig_account").update();
        assertThat(count("media")).isZero();
        var ds = (com.zaxxer.hikari.HikariDataSource) dataSource;
        var c = java.net.URI.create(ds.getJdbcUrl().substring("jdbc:".length()));
        ProcessBuilder restore = new ProcessBuilder(pgDump.replace("pg_dump", "pg_restore"), "--host", c.getHost(),
                "--port", String.valueOf(c.getPort()), "--username", ds.getUsername(), "--dbname", c.getPath().substring(1),
                "--clean", "--if-exists", "--no-owner", "--schema", "pagelens", file.toString()).redirectErrorStream(true);
        restore.environment().put("PGPASSWORD", ds.getPassword());
        Process p = restore.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        dataSource.getConnection().close(); // fresh connections see the restored schema
        assertThat(count("media")).isEqualTo(3);
        assertThat(count("ig_token")).isEqualTo(1);
    }

    @Autowired
    javax.sql.DataSource dataSource;

    // ------------------------------------------------------------------------------------------ helpers

    private static String pgDumpPath() {
        for (String candidate : new String[] {"C:\\Program Files\\PostgreSQL\\17\\bin\\pg_dump.exe", "/usr/bin/pg_dump",
                "/usr/local/bin/pg_dump", "/opt/homebrew/bin/pg_dump"}) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void stub() {
        Instant now = Instant.now();
        String page1 = fixture("media-page1.json").replace("${BASE}", fake.url())
                .replace("${RECENT_1}", IG_TS.format(now.minus(1, ChronoUnit.DAYS)))
                .replace("${RECENT_2}", IG_TS.format(now.minus(2, ChronoUnit.DAYS)));
        fake.onFixture("GET", "/v27.0/me", "ig-me.json");
        fake.on("GET", "/v27.0/" + IG + "/media", r -> Response.json(r.query().containsKey("after")
                ? fixture("media-page2.json") : page1));
        fake.onFixture("GET", "/v27.0/18000000000000003/insights", "media-insights-reel.json");
        fake.onFixture("GET", "/v27.0/18000000000000002/insights", "media-insights-feed.json");
        fake.onFixture("GET", "/v27.0/18000000000000001/insights", "media-insights-feed.json");
        fake.onFixture("GET", "/v27.0/18000000000000003/comments", "comments.json");
        fake.onJson("GET", "/v27.0/18000000000000002/comments", "{\"data\":[]}");
        fake.on("GET", "/v27.0/" + IG + "/insights", r -> switch (r.query().get("metric")) {
            case "follows_and_unfollows" -> Response.json(fixture("follows-and-unfollows.json"));
            case "follower_demographics" -> Response.json(fixture("demographics-country.json"));
            default -> Response.json(fixture("account-day.json"));
        });
    }
}
