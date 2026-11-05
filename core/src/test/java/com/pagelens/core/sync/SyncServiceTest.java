package com.pagelens.core.sync;

import static com.pagelens.testsupport.FakeGraphApi.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

import com.pagelens.core.auth.AccountConnectionService;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.SyncRunRepository.Status;
import com.pagelens.core.sync.SyncService.JobType;
import com.pagelens.core.sync.SyncService.Trigger;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** End to end: connect → sync → database, against a fake Graph API and a real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class SyncServiceTest {

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
    SyncService sync;
    @Autowired
    AccountConnectionService connections;
    @Autowired
    AccountRepository accounts;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        jdbc.sql("DELETE FROM api_call_log").update();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM sync_run").update();
    }

    @Test
    void firstFullSyncStoresEverythingAndRerunIsIdempotent() {
        stubInstagramAccount();
        long id = connectInstagram();

        assertThat(sync.run(id, JobType.FULL, Trigger.UI)).isEqualTo(Status.SUCCEEDED);

        assertThat(count("media")).isEqualTo(3);
        assertThat(count("media_insight_snapshot")).isEqualTo(3);
        assertThat(count("comment")).isEqualTo(3); // 2 threads + 1 reply
        assertThat(jdbc.sql("SELECT count(*) FROM comment WHERE parent_id IS NOT NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT media_product_type FROM media WHERE ig_media_id = '18000000000000001'")
                .query(String.class).single()).isEqualTo("FEED"); // inferred
        assertThat(jdbc.sql("SELECT avg_watch_time_ms FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = '18000000000000003'").query(Long.class).single()).isEqualTo(7400L);
        assertThat(jdbc.sql("SELECT extra_metrics FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = '18000000000000003'").query(String.class).single())
                .contains("ig_reels_video_view_total_time");
        // Feed post insights returned no views: NULL, not 0.
        assertThat(jdbc.sql("SELECT views FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = '18000000000000002'").query(Long.class).optional()).isEmpty();

        assertThat(count("account_daily_metric")).isEqualTo(4); // backfill-days=3 → 3 days + today
        assertThat(jdbc.sql("SELECT followers_count FROM account_daily_metric WHERE day = CURRENT_DATE")
                .query(Long.class).single()).isEqualTo(1250L);
        assertThat(jdbc.sql("SELECT extra_metrics FROM account_daily_metric ORDER BY day LIMIT 1")
                .query(String.class).single()).contains("\"FOLLOWER\":12");
        assertThat(count("audience_snapshot")).isEqualTo(16); // 4 breakdowns × 4 rows (fixture)

        assertThat(accounts.findById(id).orElseThrow().lastSyncedAt()).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM api_call_log WHERE endpoint LIKE '%TEST-TOKEN%' "
                + "OR endpoint LIKE '%SECRET-IN-NEXT-URL%'").query(Long.class).single()).isZero();

        // Second run: upserts, no duplicates; a fresh insight snapshot per refreshed post; audience once per day.
        assertThat(sync.run(id, JobType.FULL, Trigger.SCHEDULER)).isEqualTo(Status.SUCCEEDED);
        assertThat(count("media")).isEqualTo(3);
        assertThat(count("comment")).isEqualTo(3);
        assertThat(count("account_daily_metric")).isEqualTo(4);
        assertThat(count("audience_snapshot")).isEqualTo(16);
        assertThat(count("media_insight_snapshot")).isEqualTo(5); // 2 recent posts refreshed; old post not yet stale
    }

    @Test
    void tokenStoredEncrypted() {
        stubInstagramAccount();
        long id = connectInstagram();
        byte[] stored = jdbc.sql("SELECT encrypted_token FROM ig_token WHERE account_id = :id").param("id", id)
                .query(byte[].class).single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("TEST-TOKEN");
    }

    @Test
    void invalidTokenMarksAccountForReconnect() {
        stubInstagramAccount();
        long id = connectInstagram();
        fake.on("GET", "/v27.0/me", r -> Response.error(400, 190, "Error validating access token: Session has expired"));

        assertThat(sync.run(id, JobType.FULL, Trigger.SCHEDULER)).isEqualTo(Status.FAILED);

        var account = accounts.findById(id).orElseThrow();
        assertThat(account.status()).isEqualTo("NEEDS_RECONNECT");
        assertThat(account.statusMessage()).contains("Reconnect");
        assertThat(jdbc.sql("SELECT status FROM ig_token WHERE account_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("INVALID");
        assertThat(count("media")).isZero(); // aborted before touching media
    }

    @Test
    void onlyOneRunPerAccountAtATime() {
        stubInstagramAccount();
        long id = connectInstagram();
        jdbc.sql("INSERT INTO sync_run (account_id, job_type, triggered_by, status) VALUES (:id, 'FULL', 'mcp', 'RUNNING')")
                .param("id", id).update();

        assertThatThrownBy(() -> sync.run(id, JobType.FULL, Trigger.UI))
                .isInstanceOf(SyncService.AlreadyRunningException.class);
    }

    @Test
    void storiesAreStoredWithInsightsAndUnsupportedEdgeIsSkipped() {
        stubInstagramAccount();
        long id = connectInstagram();
        fake.on("GET", "/v27.0/" + IG + "/stories", r -> Response.json("""
                {"data":[{"id":"18100000000000001","media_type":"IMAGE","timestamp":"%s"}]}"""
                .formatted(IG_TS.format(Instant.now().minus(2, ChronoUnit.HOURS)))));
        fake.onJson("GET", "/v27.0/18100000000000001/insights",
                "{\"data\":[{\"name\":\"reach\",\"period\":\"lifetime\",\"values\":[{\"value\":44}]}]}");

        assertThat(sync.run(id, JobType.STORIES, Trigger.SCHEDULER)).isEqualTo(Status.SUCCEEDED);
        assertThat(jdbc.sql("SELECT is_story FROM media WHERE ig_media_id = '18100000000000001'")
                .query(Boolean.class).single()).isTrue();
        assertThat(count("media_insight_snapshot")).isEqualTo(1);

        // A refused edge (e.g. Instagram Login) disables polling: SKIPPED, not a failure every 3 hours.
        fake.on("GET", "/v27.0/" + IG + "/stories", r -> Response.error(400, 100, "Unsupported get request"));
        assertThat(sync.run(id, JobType.STORIES, Trigger.SCHEDULER)).isEqualTo(Status.SKIPPED);
        int storyCalls = fake.requests("/v27.0/" + IG + "/stories").size();
        assertThat(sync.run(id, JobType.STORIES, Trigger.SCHEDULER)).isEqualTo(Status.SKIPPED);
        assertThat(fake.requests("/v27.0/" + IG + "/stories")).hasSize(storyCalls); // not called again
    }

    @Test
    void disconnectPurgesAllAccountData() {
        stubInstagramAccount();
        long id = connectInstagram();
        sync.run(id, JobType.FULL, Trigger.UI);

        assertThat(connections.disconnect(id, Actor.UI)).isTrue();

        for (String table : List.of("ig_account", "ig_token", "media", "media_insight_snapshot", "comment",
                "account_daily_metric", "audience_snapshot")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(jdbc.sql("SELECT action FROM audit_log ORDER BY id").query(String.class).list())
                .containsExactly("account.connect", "account.disconnect");
    }

    @Test
    void facebookLoginConnectsEveryPageLinkedAccount() {
        fake.on("GET", "/v27.0/oauth/access_token", r -> Response.json(r.query().containsKey("code")
                ? "{\"access_token\":\"FB-SHORT\",\"token_type\":\"bearer\",\"expires_in\":3600}"
                : fixture("oauth-long-lived.json")));
        fake.onFixture("GET", "/v27.0/me/accounts", "fb-me-accounts.json");
        fake.onFixture("GET", "/v27.0/17841400000000099", "fb-ig-user.json");

        var connected = connections.completeAuthorization(LoginType.FACEBOOK_LOGIN, "code123");

        assertThat(connected).singleElement().satisfies(a -> {
            assertThat(a.loginType()).isEqualTo(LoginType.FACEBOOK_LOGIN);
            assertThat(a.facebookPageId()).isEqualTo("100000000000001");
            assertThat(a.followersCount()).isEqualTo(5000L);
        });
        assertThat(jdbc.sql("SELECT expires_at IS NOT NULL FROM ig_token").query(Boolean.class).single()).isTrue();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private long connectInstagram() {
        return connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
    }

    private void stubInstagramAccount() {
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

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
