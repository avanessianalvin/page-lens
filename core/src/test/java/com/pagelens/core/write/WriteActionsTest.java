package com.pagelens.core.write;

import static com.pagelens.testsupport.FakeGraphApi.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.sync.SyncService;
import com.pagelens.core.write.PublishModels.Item;
import com.pagelens.core.write.PublishModels.ItemType;
import com.pagelens.core.write.PublishModels.JobStatus;
import com.pagelens.core.write.PublishModels.Kind;
import com.pagelens.core.write.PublishModels.Options;
import com.pagelens.core.write.PublishModels.PublishRequest;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** Write actions against a fake Graph API with the switch ON. */
@SpringBootTest(properties = {"pagelens.write-actions-enabled=true", "pagelens.publish.poll-initial=10ms",
        "pagelens.publish.poll-max=20ms"})
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class WriteActionsTest {

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
    CommentActions comments;
    @Autowired
    PublishService publisher;
    @Autowired
    UploadStore uploads;
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
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM sync_run").update();
        stubReadApi();
        accountId = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        sync.run(accountId, SyncService.JobType.FULL, SyncService.Trigger.SCHEDULER);
        // Grant the publish scope, as a fresh OAuth connect would.
        jdbc.sql("UPDATE ig_token SET scopes = 'instagram_business_basic,instagram_business_content_publish'").update();
        fake.onJson("GET", "/v27.0/" + IG + "/content_publishing_limit",
                "{\"data\":[{\"quota_usage\":3,\"config\":{\"quota_total\":50,\"quota_duration\":86400}}]}");
    }

    // ------------------------------------------------------------------------------------------ comments

    @Test
    void replyGoesToTheTopLevelCommentAndIsStoredWithoutLoggingText() {
        fake.onJson("POST", "/v27.0/17900000000000002/replies", "{\"id\":\"17900000000000777\"}");
        long reply = commentId("17900000000000003"); // a reply: PageLens must target its parent

        WriteResult r = comments.reply(reply, "ممنون از لطفت 🙏", false, Actor.UI);

        assertThat(r.ok()).isTrue();
        assertThat(r.details()).containsEntry("newCommentId", "17900000000000777");
        var sent = fake.requests("/v27.0/17900000000000002/replies").getFirst();
        assertThat(FakeGraphApi.parse(sent.body())).containsEntry("message", "ممنون از لطفت 🙏")
                .containsKey("access_token");
        assertThat(sent.query()).doesNotContainKey("access_token"); // token in the body, not the URL
        assertThat(jdbc.sql("SELECT parent_id FROM comment WHERE ig_comment_id = '17900000000000777'")
                .query(Long.class).single()).isEqualTo(commentId("17900000000000002"));
        // Audit: action recorded, comment text not.
        String audit = jdbc.sql("SELECT action || ' ' || outcome || ' ' || payload_summary FROM audit_log "
                + "WHERE action = 'comment.reply'").query(String.class).single();
        assertThat(audit).startsWith("comment.reply ok").doesNotContain("ممنون");
    }

    @Test
    void dryRunSendsNothing() {
        int before = fake.requests().size();
        WriteResult r = comments.reply(commentId("17900000000000002"), "hello", true, Actor.MCP);
        assertThat(r.ok()).isTrue();
        assertThat(r.dryRun()).isTrue();
        assertThat(fake.requests()).hasSize(before);
        assertThat(jdbc.sql("SELECT outcome FROM audit_log WHERE action = 'comment.reply'").query(String.class).single())
                .isEqualTo("dry_run_ok");
    }

    @Test
    void hideUnhideAndDelete() {
        fake.onJson("POST", "/v27.0/17900000000000001", "{\"success\":true}");
        fake.onJson("DELETE", "/v27.0/17900000000000001", "{\"success\":true}");
        long id = commentId("17900000000000001");

        assertThat(comments.setHidden(id, true, false, Actor.UI).ok()).isTrue();
        assertThat(FakeGraphApi.parse(fake.requests("/v27.0/17900000000000001").getFirst().body())).containsEntry("hide", "true");
        assertThat(jdbc.sql("SELECT hidden FROM comment WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();

        // Instagram doesn't allow replies to hidden comments: refused before any call.
        WriteResult refused = comments.reply(id, "hi", false, Actor.UI);
        assertThat(refused.ok()).isFalse();
        assertThat(refused.errors().getFirst()).contains("hidden");

        assertThat(comments.delete(id, false, Actor.UI).ok()).isTrue();
        assertThat(fake.requests("/v27.0/17900000000000001")).extracting(FakeGraphApi.Request::method).contains("DELETE");
        assertThat(jdbc.sql("SELECT count(*) FROM comment WHERE id = :id").param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void instagramErrorsBecomeReadableFailuresAndAreAudited() {
        fake.on("POST", "/v27.0/17900000000000002/replies",
                r -> Response.error(403, 200, "Permissions error"));
        WriteResult r = comments.reply(commentId("17900000000000002"), "hi", false, Actor.UI);
        assertThat(r.ok()).isFalse();
        assertThat(r.errors().getFirst()).contains("Reconnect");
        assertThat(fake.requests("/v27.0/17900000000000002/replies")).hasSize(1); // writes are never retried
        assertThat(jdbc.sql("SELECT outcome FROM audit_log WHERE action = 'comment.reply'").query(String.class).single())
                .startsWith("failed");
    }

    // ------------------------------------------------------------------------------------------ publishing

    @Test
    void publishesAnImageThroughTheContainerWorkflowExactlyOnce() throws Exception {
        fake.media("/img/photo.jpg", "image/jpeg", 120_000);
        AtomicInteger statusCalls = new AtomicInteger();
        fake.onJson("POST", "/v27.0/" + IG + "/media", "{\"id\":\"c-100\"}");
        fake.on("GET", "/v27.0/c-100", r -> Response.json(statusCalls.incrementAndGet() < 3
                ? "{\"status_code\":\"IN_PROGRESS\"}" : "{\"status_code\":\"FINISHED\"}"));
        fake.onJson("POST", "/v27.0/" + IG + "/media_publish", "{\"id\":\"18000000000000999\"}");
        fake.onJson("GET", "/v27.0/18000000000000999", """
                {"id":"18000000000000999","media_type":"IMAGE","permalink":"https://www.instagram.com/p/NEW/",
                 "timestamp":"2026-11-05T10:00:00+0000","caption":"Բարեւ #new"}""");

        PublishRequest req = new PublishRequest(accountId, Kind.IMAGE, "Բարեւ #new",
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/photo.jpg", null)),
                new Options(null, null, null, "A cup of coffee"), "key-1");

        WriteResult dry = publisher.validate(req, Actor.UI);
        assertThat(dry.ok()).as(dry.errors().toString()).isTrue();
        assertThat(dry.details()).containsEntry("quotaUsed", 3).containsEntry("quotaTotal", 50);
        assertThat(posts("/v27.0/" + IG + "/media")).isEmpty(); // dry run created nothing

        WriteResult submitted = publisher.submit(req, Actor.UI);
        long jobId = (Long) submitted.details().get("jobId");
        var job = publisher.await(jobId, Duration.ofSeconds(10));

        assertThat(job.status()).isEqualTo(JobStatus.PUBLISHED);
        assertThat(job.igMediaId()).isEqualTo("18000000000000999");
        assertThat(job.permalink()).isEqualTo("https://www.instagram.com/p/NEW/");
        var container = FakeGraphApi.parse(posts("/v27.0/" + IG + "/media").getFirst().body());
        assertThat(container).containsEntry("image_url", fake.url() + "/img/photo.jpg")
                .containsEntry("caption", "Բարեւ #new").containsEntry("alt_text", "A cup of coffee")
                .doesNotContainKey("media_type");
        assertThat(FakeGraphApi.parse(fake.requests("/v27.0/" + IG + "/media_publish").getFirst().body()))
                .containsEntry("creation_id", "c-100");
        // The new post is in the local database right away.
        assertThat(jdbc.sql("SELECT count(*) FROM media WHERE ig_media_id = '18000000000000999'").query(Long.class).single())
                .isEqualTo(1);

        // Same idempotency key again: no second job, no second publish.
        WriteResult again = publisher.submit(req, Actor.UI);
        assertThat(again.details()).containsEntry("jobId", jobId);
        assertThat(again.warnings()).anySatisfy(w -> assertThat(w).contains("already submitted"));
        Thread.sleep(200);
        assertThat(fake.requests("/v27.0/" + IG + "/media_publish")).hasSize(1);
        assertThat(jdbc.sql("SELECT string_agg(action || ':' || outcome, ',' ORDER BY id) FROM audit_log "
                + "WHERE action LIKE 'publish.%'").query(String.class).single())
                .isEqualTo("publish.validate:dry_run_ok,publish.submit:queued,publish.publish:ok,publish.submit:duplicate");
    }

    @Test
    void lostPublishResponseIsResolvedByContainerStatusNotByPublishingAgain() throws Exception {
        fake.media("/img/a.jpg", "image/jpeg", 1000);
        AtomicInteger publishCalls = new AtomicInteger();
        fake.onJson("POST", "/v27.0/" + IG + "/media", "{\"id\":\"c-200\"}");
        fake.on("GET", "/v27.0/c-200", r -> Response.json(publishCalls.get() == 0
                ? "{\"status_code\":\"FINISHED\"}" : "{\"status_code\":\"PUBLISHED\"}"));
        fake.on("POST", "/v27.0/" + IG + "/media_publish", r -> {
            publishCalls.incrementAndGet();
            return new Response(500, "{\"error\":{\"message\":\"Service temporarily unavailable\",\"code\":2,\"is_transient\":true}}", Map.of());
        });

        long jobId = (Long) publisher.submit(new PublishRequest(accountId, Kind.IMAGE, null,
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/a.jpg", null)), null, "key-2"), Actor.MCP).details().get("jobId");
        var job = publisher.await(jobId, Duration.ofSeconds(10));

        assertThat(job.status()).isEqualTo(JobStatus.PUBLISHED);
        assertThat(publishCalls).hasValue(1); // never re-sent
    }

    @Test
    void carouselCreatesChildrenThenParent() throws Exception {
        fake.media("/img/1.jpg", "image/jpeg", 1000).media("/img/2.jpg", "image/jpeg", 1000);
        AtomicInteger n = new AtomicInteger();
        fake.on("POST", "/v27.0/" + IG + "/media", r -> Response.json("{\"id\":\"c-" + n.incrementAndGet() + "\"}"));
        fake.onJson("GET", "/v27.0/c-3", "{\"status_code\":\"FINISHED\"}");
        fake.onJson("POST", "/v27.0/" + IG + "/media_publish", "{\"id\":\"m-car\"}");
        fake.onJson("GET", "/v27.0/m-car", "{\"id\":\"m-car\",\"media_type\":\"CAROUSEL_ALBUM\",\"permalink\":\"p\"}");

        long jobId = (Long) publisher.submit(new PublishRequest(accountId, Kind.CAROUSEL, "Two photos",
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/1.jpg", null),
                        new Item(ItemType.IMAGE, fake.url() + "/img/2.jpg", null)), null, "key-3"), Actor.UI)
                .details().get("jobId");
        assertThat(publisher.await(jobId, Duration.ofSeconds(10)).status()).isEqualTo(JobStatus.PUBLISHED);

        var calls = posts("/v27.0/" + IG + "/media").stream().map(r -> FakeGraphApi.parse(r.body())).toList();
        assertThat(calls.get(0)).containsEntry("is_carousel_item", "true").doesNotContainKey("caption");
        assertThat(calls.get(2)).containsEntry("media_type", "CAROUSEL").containsEntry("children", "c-1,c-2")
                .containsEntry("caption", "Two photos");
    }

    @Test
    void localVideoUploadUsesResumableUploadOnFacebookLoginOnly() throws Exception {
        var upload = uploads.save(new ByteArrayInputStream(new byte[4096]), "clip.mp4");
        Item video = new Item(ItemType.VIDEO, null, upload.id());

        // Instagram Login account: refused (Meta documents resumable upload for Facebook Login only).
        WriteResult igLogin = publisher.validate(new PublishRequest(accountId, Kind.REEL, "x", List.of(video), null, "k"), Actor.UI);
        assertThat(igLogin.ok()).isFalse();
        assertThat(igLogin.errors()).anySatisfy(e -> assertThat(e).contains("Facebook Login"));

        jdbc.sql("UPDATE ig_account SET login_type = 'FACEBOOK_LOGIN'").update();
        jdbc.sql("UPDATE ig_token SET scopes = 'instagram_basic,instagram_content_publish'").update();
        fake.onJson("POST", "/v27.0/" + IG + "/media",
                "{\"id\":\"c-vid\",\"uri\":\"https://rupload.facebook.com/ig-api-upload/v27.0/c-vid\"}");
        fake.onJson("POST", "/ig-api-upload/v27.0/c-vid", "{\"success\":true,\"message\":\"Upload successful.\"}");
        fake.onJson("GET", "/v27.0/c-vid", "{\"status_code\":\"FINISHED\"}");
        fake.onJson("POST", "/v27.0/" + IG + "/media_publish", "{\"id\":\"m-vid\"}");
        fake.onJson("GET", "/v27.0/m-vid", "{\"id\":\"m-vid\",\"media_type\":\"VIDEO\",\"media_product_type\":\"REELS\"}");
        fake.onJson("GET", "/v27.0/" + IG + "/content_publishing_limit", "{\"data\":[{\"quota_usage\":0,\"config\":{\"quota_total\":50}}]}");

        long jobId = (Long) publisher.submit(new PublishRequest(accountId, Kind.REEL, "My reel", List.of(video),
                new Options(true, null, 1500, null), "key-4"), Actor.UI).details().get("jobId");
        var job = publisher.await(jobId, Duration.ofSeconds(10));
        assertThat(job.status()).as(String.valueOf(job.errorMessage())).isEqualTo(JobStatus.PUBLISHED);

        var create = FakeGraphApi.parse(posts("/v27.0/" + IG + "/media").getFirst().body());
        assertThat(create).containsEntry("upload_type", "resumable").containsEntry("media_type", "REELS")
                .containsEntry("share_to_feed", "true").containsEntry("thumb_offset", "1500");
        var sent = fake.requests("/ig-api-upload/v27.0/c-vid").getFirst();
        assertThat(sent.bodyBytes()).isEqualTo(4096);
        assertThat(sent.headers()).containsEntry("authorization", "OAuth TEST-TOKEN-123")
                .containsEntry("offset", "0").containsEntry("file_size", "4096");
        assertThat(uploads.resolve(upload.id())).isEmpty(); // staged file removed after publishing
    }

    @Test
    void validationCatchesInstagramRules() {
        fake.media("/img/pic.png", "image/png", 1000).media("/img/huge.jpg", "image/jpeg", 9L * 1024 * 1024);
        String tags = String.join(" ", java.util.stream.IntStream.range(0, 31).mapToObj(i -> "#t" + i).toList());

        WriteResult r = publisher.validate(new PublishRequest(accountId, Kind.IMAGE, tags,
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/pic.png", null)), null, null), Actor.UI);
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("JPEG"))
                .anySatisfy(e -> assertThat(e).contains("31 hashtags"));

        assertThat(publisher.validate(new PublishRequest(accountId, Kind.IMAGE, null,
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/huge.jpg", null)), null, null), Actor.UI).errors())
                .anySatisfy(e -> assertThat(e).contains("8 MB"));
        assertThat(publisher.validate(new PublishRequest(accountId, Kind.CAROUSEL, null,
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/pic.png", null)), null, null), Actor.UI).errors())
                .anySatisfy(e -> assertThat(e).contains("2 to 20"));
        assertThat(publisher.validate(new PublishRequest(accountId, Kind.IMAGE, "x".repeat(2201),
                List.of(new Item(ItemType.IMAGE, "ftp://x/y.jpg", null)), null, null), Actor.UI).errors())
                .anySatisfy(e -> assertThat(e).contains("2201 characters"))
                .anySatisfy(e -> assertThat(e).contains("http(s)"));

        jdbc.sql("UPDATE ig_token SET scopes = 'instagram_business_basic'").update();
        assertThat(publisher.validate(new PublishRequest(accountId, Kind.STORY, null,
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/huge.jpg", null)), null, null), Actor.UI).errors())
                .anySatisfy(e -> assertThat(e).contains("Reconnect"));
    }

    @Test
    void interruptedJobsAreResolvedByInstagramsStatusNeverRepublished() {
        jdbc.sql("""
                INSERT INTO publish_job (account_id, idempotency_key, kind, items_json, item_count, actor, status,
                                         container_id, updated_at)
                VALUES (:a, 'stale-published', 'IMAGE', '[]', 1, 'ui', 'PUBLISHING', 'c-pub', now() - interval '1 hour'),
                       (:a, 'stale-unknown', 'IMAGE', '[]', 1, 'ui', 'PROCESSING', 'c-fin', now() - interval '1 hour'),
                       (:a, 'fresh', 'IMAGE', '[]', 1, 'ui', 'PROCESSING', 'c-new', now())
                """).param("a", accountId).update();
        fake.onJson("GET", "/v27.0/c-pub", "{\"status_code\":\"PUBLISHED\"}");
        fake.onJson("GET", "/v27.0/c-fin", "{\"status_code\":\"FINISHED\"}");

        assertThat(publisher.recoverStale()).isEqualTo(2);

        assertThat(status("stale-published")).isEqualTo("PUBLISHED");
        assertThat(status("stale-unknown")).isEqualTo("FAILED");
        assertThat(status("fresh")).isEqualTo("PROCESSING"); // recent: maybe still running elsewhere, left alone
        assertThat(posts("/v27.0/" + IG + "/media_publish")).isEmpty(); // nothing was published by recovery
    }

    @Test
    void retryReusesTheExistingContainer() throws Exception {
        fake.media("/img/r.jpg", "image/jpeg", 1000);
        fake.onJson("POST", "/v27.0/" + IG + "/media", "{\"id\":\"c-retry\"}");
        AtomicInteger publishCalls = new AtomicInteger();
        fake.onJson("GET", "/v27.0/c-retry", "{\"status_code\":\"FINISHED\"}");
        fake.on("POST", "/v27.0/" + IG + "/media_publish", r -> publishCalls.incrementAndGet() == 1
                ? Response.error(400, 9007, "Media is not ready for publishing")
                : Response.json("{\"id\":\"m-retry\"}"));
        fake.onJson("GET", "/v27.0/m-retry", "{\"id\":\"m-retry\",\"media_type\":\"IMAGE\"}");

        long jobId = (Long) publisher.submit(new PublishRequest(accountId, Kind.IMAGE, null,
                List.of(new Item(ItemType.IMAGE, fake.url() + "/img/r.jpg", null)), null, "key-retry"), Actor.UI)
                .details().get("jobId");
        assertThat(publisher.await(jobId, Duration.ofSeconds(10)).status()).isEqualTo(JobStatus.FAILED);

        assertThat(publisher.retry(jobId, Actor.UI).ok()).isTrue();
        var job = publisher.await(jobId, Duration.ofSeconds(10));

        assertThat(job.status()).isEqualTo(JobStatus.PUBLISHED);
        assertThat(posts("/v27.0/" + IG + "/media")).hasSize(1); // the container was reused, not re-created
        assertThat(publisher.retry(jobId, Actor.UI).errors().getFirst()).contains("Only failed jobs");
    }

    @Test
    void privateAddressesAreRejected() {
        assertThat(MediaProbe.isPrivate("localhost")).isTrue();
        assertThat(MediaProbe.isPrivate("127.0.0.1")).isTrue();
        assertThat(MediaProbe.isPrivate("192.168.1.20")).isTrue();
        assertThat(MediaProbe.isPrivate("10.0.0.1")).isTrue();
        assertThat(MediaProbe.isPrivate("8.8.8.8")).isFalse();
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** POSTs only: the sync's GET /media shares the path with container creation. */
    private static List<FakeGraphApi.Request> posts(String path) {
        return fake.requests(path).stream().filter(r -> r.method().equals("POST")).toList();
    }

    private String status(String key) {
        return jdbc.sql("SELECT status FROM publish_job WHERE idempotency_key = :k").param("k", key)
                .query(String.class).single();
    }

    private long commentId(String igId) {
        return jdbc.sql("SELECT id FROM comment WHERE ig_comment_id = :ig").param("ig", igId).query(Long.class).single();
    }

    private void stubReadApi() {
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
