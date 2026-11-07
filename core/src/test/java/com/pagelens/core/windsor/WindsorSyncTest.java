package com.pagelens.core.windsor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.pagelens.core.auth.TokenService;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.SyncRunRepository.Status;
import com.pagelens.core.sync.SyncService;
import com.pagelens.core.sync.SyncService.JobType;
import com.pagelens.core.sync.SyncService.Trigger;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Request;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** Windsor.ai account discovery and sync, against a fake connector API and a real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class WindsorSyncTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final String KEY = "TEST-WINDSOR-KEY-123";
    static final String ACC = "17841400000000077";
    static final String OTHER = "17841400000000088";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
        r.add("pagelens.windsor.base-url", fake::url);
        r.add("pagelens.windsor.api-key", () -> KEY);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired
    WindsorAccounts windsorAccounts;
    @Autowired
    SyncService sync;
    @Autowired
    AccountRepository accounts;
    @Autowired
    TokenService tokens;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        jdbc.sql("DELETE FROM api_call_log").update();
        jdbc.sql("DELETE FROM sync_run").update();
        fake.on("GET", "/instagram", WindsorSyncTest::answer);
    }

    /** Answers by the requested field set, like Windsor's per-table queries. */
    static Response answer(Request r) {
        if (!KEY.equals(r.query().get("api_key"))) {
            return new Response(401, "{\"error\":\"Not authorized\"}", Map.of());
        }
        Set<String> f = Set.of(r.query().get("fields").split(","));
        if (f.contains("comment_id")) {
            return Response.json("""
                    {"data":[
                     {"account_id":"%1$s","media_id":"m1","comment_id":"c1","comment_text":"Շատ լավ է","comment_timestamp":"2026-11-01T10:00:00+0000","comment_username":"ani","comment_like_count":2,"comment_parent_id":null},
                     {"account_id":"%1$s","media_id":"m1","comment_id":"c2","comment_text":"thanks!","comment_timestamp":"2026-11-01T11:00:00+0000","comment_username":"me","comment_like_count":0,"comment_parent_id":"c1"},
                     {"account_id":"%1$s","media_id":"m2","comment_id":"c3","comment_text":"🔥","comment_timestamp":"2026-11-02T09:00:00+0000","comment_username":"aram","comment_like_count":null,"comment_parent_id":""},
                     {"account_id":"%1$s","media_id":"unknown","comment_id":"c9","comment_text":"x","comment_timestamp":"2026-11-02T09:00:00+0000","comment_username":"z"}
                    ]}""".formatted(ACC));
        }
        if (f.contains("media_caption")) {
            return Response.json("""
                    {"data":[
                     {"account_id":"%1$s","media_id":"m1","media_caption":"Photo #yerevan","media_permalink":"https://www.instagram.com/p/AAA/","media_type":"IMAGE","media_product_type":"FEED","timestamp":"2026-11-01T08:00:00+0000","media_like_count":40,"media_comments_count":2,"media_reach":"900","media_views":1200,"media_saved":5,"media_shares":3,"media_engagement":50},
                     {"account_id":"%1$s","media_id":"m2","media_caption":"Reel","media_type":"REEL","media_product_type":"FEED","timestamp":"2026-11-02 07:30:00","media_like_count":80,"media_comments_count":1,"media_reach":3000,"media_views":5000,"media_reel_avg_watch_time":7400,"media_reel_total_watch_time":36000000,"media_reposts":4},
                     {"account_id":"%2$s","media_id":"x1","media_type":"IMAGE","timestamp":"2026-11-02T07:30:00+0000"}
                    ]}""".formatted(ACC, OTHER));
        }
        if (f.contains("followers_count")) {
            return Response.json("""
                    {"data":[
                     {"account_id":"%1$s","username":"pagelens_test","name":"PageLens Test","followers_count":1500,"follows_count":120,"media_count":42},
                     {"account_id":"%2$s","username":"other_one","name":"Other","followers_count":10,"follows_count":1,"media_count":3}
                    ]}""".formatted(ACC, OTHER));
        }
        if (f.contains("follower_count")) {
            return Response.json("""
                    {"data":[
                     {"account_id":"%1$s","date":"2026-11-04","reach":700,"follower_count":12},
                     {"account_id":"%1$s","date":"2026-11-05","reach":800,"follower_count":null}
                    ]}""".formatted(ACC));
        }
        if (f.contains("accounts_engaged")) {
            return Response.json("""
                    {"data":[
                     {"account_id":"%1$s","date":"2026-11-04","views":2000,"accounts_engaged":150,"total_interactions":300,"likes":250,"comments":20,"shares":10,"saves":20,"replies":0,"profile_links_taps":4},
                     {"account_id":"%1$s","date":"2026-11-05","views":2100}
                    ]}""".formatted(ACC));
        }
        if (f.contains("audience_country_name")) {
            return Response.json("""
                    {"data":[{"account_id":"%1$s","audience_country_name":"AM","audience_country_size":900},
                             {"account_id":"%1$s","audience_country_name":"US","audience_country_size":200}]}""".formatted(ACC));
        }
        if (f.contains("audience_age_name") || f.contains("audience_gender_name") || f.contains("city")) {
            return Response.json("{\"data\":[]}");
        }
        if (f.contains("story_id")) {
            return Response.json("""
                    {"data":[{"account_id":"%1$s","story_id":"s1","story_timestamp":"2026-11-05T18:00:00+0000","story_views":300,"story_reach":250,"story_replies":2,"story_exits":7}]}""".formatted(ACC));
        }
        return new Response(400, "{\"error\":\"unexpected fields\"}", Map.of());
    }

    private long discover() {
        windsorAccounts.discover(Actor.UI);
        return accounts.findByIgUserId(ACC).orElseThrow().id();
    }

    @Test
    void discoverAddsWindsorAccountsButKeepsDirectConnections() {
        accounts.upsertConnected(OTHER, LoginType.INSTAGRAM_LOGIN, null, "other_one");

        List<WindsorAccounts.Found> found = windsorAccounts.discover(Actor.UI);

        assertThat(found).hasSize(2);
        assertThat(accounts.findByIgUserId(ACC).orElseThrow().loginType()).isEqualTo(LoginType.WINDSOR);
        assertThat(accounts.findByIgUserId(ACC).orElseThrow().followersCount()).isEqualTo(1500L);
        assertThat(accounts.findByIgUserId(OTHER).orElseThrow().loginType()).isEqualTo(LoginType.INSTAGRAM_LOGIN);
        assertThat(found).filteredOn(WindsorAccounts.Found::added).extracting(WindsorAccounts.Found::igUserId)
                .containsExactly(ACC);
    }

    @Test
    void fullSyncStoresPostsCommentsDailyAndAudience() {
        long id = discover();

        assertThat(sync.run(id, JobType.FULL, Trigger.UI)).isEqualTo(Status.SUCCEEDED);

        assertThat(count("SELECT count(*) FROM media WHERE account_id = " + id)).isEqualTo(2); // x1 belongs to OTHER
        assertThat(jdbc.sql("SELECT media_type || '/' || media_product_type FROM media WHERE ig_media_id = 'm2'")
                .query(String.class).single()).isEqualTo("VIDEO/REELS");
        assertThat(jdbc.sql("SELECT reach FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = 'm1'").query(Long.class).single()).isEqualTo(900L);
        assertThat(jdbc.sql("SELECT avg_watch_time_ms FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = 'm2'").query(Long.class).single()).isEqualTo(7400L);
        assertThat(jdbc.sql("SELECT extra_metrics FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = 'm2'").query(String.class).single()).contains("\"reposts\":4");

        assertThat(count("SELECT count(*) FROM comment")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM comment WHERE parent_id IS NOT NULL")).isEqualTo(1);

        assertThat(jdbc.sql("SELECT reach FROM account_daily_metric WHERE account_id = :a AND day = DATE '2026-11-04'")
                .param("a", id).query(Long.class).single()).isEqualTo(700L);
        assertThat(jdbc.sql("SELECT views FROM account_daily_metric WHERE account_id = :a AND day = DATE '2026-11-05'")
                .param("a", id).query(Long.class).single()).isEqualTo(2100L);
        assertThat(jdbc.sql("SELECT extra_metrics FROM account_daily_metric WHERE account_id = :a AND day = DATE '2026-11-04'")
                .param("a", id).query(String.class).single()).contains("\"follower_count\":12");
        assertThat(count("SELECT count(*) FROM account_daily_metric WHERE followers_count = 1500")).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM audience_snapshot WHERE dimension = 'country'")).isEqualTo(2);

        // The API key never reaches the database.
        assertThat(jdbc.sql("SELECT endpoint FROM api_call_log").query(String.class).list())
                .isNotEmpty().allSatisfy(e -> assertThat(e).doesNotContain(KEY).contains("api_key=***"));

        assertThat(sync.run(id, JobType.FULL, Trigger.UI)).isEqualTo(Status.SUCCEEDED);
        assertThat(count("SELECT count(*) FROM media WHERE account_id = " + id)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM comment")).isEqualTo(3);
    }

    @Test
    void storiesJobStoresLiveStories() {
        long id = discover();

        assertThat(sync.run(id, JobType.STORIES, Trigger.SCHEDULER)).isEqualTo(Status.SUCCEEDED);

        assertThat(jdbc.sql("SELECT is_story FROM media WHERE ig_media_id = 's1'").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT views FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = 's1'").query(Long.class).single()).isEqualTo(300L);
    }

    @Test
    void rejectedKeyFailsTheRunWithoutLeakingIt() {
        long id = discover();
        fake.on("GET", "/instagram", r -> new Response(401, "{\"error\":\"Not authorized\"}", Map.of()));

        assertThat(sync.run(id, JobType.FULL, Trigger.UI)).isEqualTo(Status.FAILED);

        String error = jdbc.sql("SELECT error_message FROM sync_run ORDER BY id DESC LIMIT 1").query(String.class).single();
        assertThat(error).contains("API key").doesNotContain(KEY);
        assertThat(fake.requests("/instagram")).hasSize(2); // discover + profile; the run stopped there
    }

    @Test
    void failingStepsMakeThePartialRunVisible() {
        long id = discover();
        fake.on("GET", "/instagram", r -> r.query().get("fields").contains("media_caption")
                ? new Response(500, "{\"error\":\"boom\"}", Map.of()) : answer(r));

        assertThat(sync.run(id, JobType.FULL, Trigger.UI)).isEqualTo(Status.PARTIAL);

        assertThat(jdbc.sql("SELECT error_message FROM sync_run ORDER BY id DESC LIMIT 1").query(String.class).single())
                .contains("media: HTTP 500");
    }

    @Test
    void importedAccountsAreSkippedAndReadOnlyAccountsHaveNoToken() {
        long imported = accounts.upsertReadOnlySource("17841400000000099", LoginType.IMPORT, "csv_only");
        assertThat(sync.run(imported, JobType.FULL, Trigger.UI)).isEqualTo(Status.SKIPPED);

        long id = discover();
        assertThatThrownBy(() -> tokens.ref(accounts.findById(id).orElseThrow()))
                .hasMessageContaining("read-only").hasMessageContaining("Windsor.ai");
    }

    @Test
    void importAccountUpgradesToWindsorButNotBack() {
        accounts.upsertReadOnlySource(ACC, LoginType.IMPORT, ACC);
        discover();
        assertThat(accounts.findByIgUserId(ACC).orElseThrow().loginType()).isEqualTo(LoginType.WINDSOR);
        assertThat(accounts.findByIgUserId(ACC).orElseThrow().username()).isEqualTo("pagelens_test");

        accounts.upsertReadOnlySource(ACC, LoginType.IMPORT, "whatever");
        assertThat(accounts.findByIgUserId(ACC).orElseThrow().loginType()).isEqualTo(LoginType.WINDSOR);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
