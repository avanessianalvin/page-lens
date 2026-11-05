package com.pagelens;

import static com.pagelens.testsupport.FakeGraphApi.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pagelens.core.auth.AccountConnectionService;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.sync.SyncService;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** Write endpoints with the switch ON. */
@SpringBootTest(properties = {"pagelens.write-actions-enabled=true", "pagelens.publish.poll-initial=10ms"})
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class PageLensWriteApiTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final String IG = "17841400000000001";
    static final RequestPostProcessor OWNER = user("admin").roles("OWNER");
    static final DateTimeFormatter IG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
        String hash = new BCryptPasswordEncoder(4).encode("irrelevant-password");
        r.add("pagelens.web.password-hash", () -> hash);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountConnectionService connections;
    @Autowired
    SyncService sync;

    long accountId;

    @BeforeEach
    void setUp() {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        jdbc.sql("DELETE FROM sync_run").update();
        Instant now = Instant.now();
        String page1 = fixture("media-page1.json").replace("${BASE}", fake.url())
                .replace("${RECENT_1}", IG_TS.format(now.minus(1, ChronoUnit.DAYS)))
                .replace("${RECENT_2}", IG_TS.format(now.minus(2, ChronoUnit.DAYS)));
        fake.onFixture("GET", "/v27.0/me", "ig-me.json");
        fake.on("GET", "/v27.0/" + IG + "/media", r -> Response.json(r.query().containsKey("after")
                ? fixture("media-page2.json") : page1));
        fake.onFixture("GET", "/v27.0/18000000000000003/comments", "comments.json");
        fake.onJson("GET", "/v27.0/18000000000000002/comments", "{\"data\":[]}");
        accountId = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        sync.run(accountId, SyncService.JobType.FULL, SyncService.Trigger.UI);
        jdbc.sql("UPDATE ig_token SET scopes = 'instagram_business_content_publish'").update();
        fake.onJson("GET", "/v27.0/" + IG + "/content_publishing_limit",
                "{\"data\":[{\"quota_usage\":1,\"config\":{\"quota_total\":50}}]}");
    }

    @Test
    void replyAndHideThroughTheApi() {
        long comment = jdbc.sql("SELECT id FROM comment WHERE ig_comment_id = '17900000000000002'").query(Long.class).single();
        fake.onJson("POST", "/v27.0/17900000000000002/replies", "{\"id\":\"17900000000000888\"}");
        fake.onJson("POST", "/v27.0/17900000000000002", "{\"success\":true}");

        assertThat(mvc.post().uri("/api/comments/" + comment + "/reply").with(OWNER).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Thanks!\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.details.newCommentId").isEqualTo("17900000000000888");
        assertThat(mvc.post().uri("/api/comments/" + comment + "/hide").with(OWNER).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true}")).hasStatusOk();
        assertThat(mvc.post().uri("/api/comments/" + comment + "/reply").with(OWNER).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"\"}")).hasStatus(422)
                .bodyJson().extractingPath("$.errors[0]").asString().contains("empty");
    }

    @Test
    void publishDryRunThenQueueAndPollStatus() throws Exception {
        fake.media("/img/x.jpg", "image/jpeg", 2000);
        fake.onJson("POST", "/v27.0/" + IG + "/media", "{\"id\":\"c-1\"}");
        fake.onJson("GET", "/v27.0/c-1", "{\"status_code\":\"FINISHED\"}");
        fake.onJson("POST", "/v27.0/" + IG + "/media_publish", "{\"id\":\"m-1\"}");
        fake.onJson("GET", "/v27.0/m-1", "{\"id\":\"m-1\",\"media_type\":\"IMAGE\",\"permalink\":\"https://instagram.com/p/m1\"}");
        String body = "{\"accountId\":" + accountId + ",\"kind\":\"IMAGE\",\"caption\":\"hello\",\"items\":[{\"type\":\"IMAGE\",\"url\":\""
                + fake.url() + "/img/x.jpg\"}],\"idempotencyKey\":\"web-key-1\"";

        assertThat(mvc.post().uri("/api/publish").with(OWNER).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body + ",\"dryRun\":true}")).hasStatusOk().bodyJson().extractingPath("$.dryRun").isEqualTo(true);

        var queued = mvc.post().uri("/api/publish").with(OWNER).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body + "}").exchange();
        assertThat(queued).hasStatus(202);
        Number jobId = com.jayway.jsonpath.JsonPath.read(queued.getResponse().getContentAsString(), "$.details.jobId");

        String status = "";
        for (int i = 0; i < 50 && !status.equals("PUBLISHED"); i++) {
            Thread.sleep(100);
            status = com.jayway.jsonpath.JsonPath.read(mvc.get().uri("/api/publish/" + jobId + "/status").with(OWNER)
                    .exchange().getResponse().getContentAsString(), "$.status");
        }
        assertThat(status).isEqualTo("PUBLISHED");
        assertThat(mvc.get().uri("/api/publish?accountId=" + accountId).with(OWNER)).bodyJson()
                .extractingPath("$[0].permalink").isEqualTo("https://instagram.com/p/m1");
    }

    @Test
    void videoUploadIsStagedAndOtherFilesAreRefused() {
        assertThat(mvc.post().uri("/api/publish/uploads").with(OWNER).with(csrf())
                .multipart().file(new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[1024])))
                .hasStatusOk().bodyJson().extractingPath("$.size").isEqualTo(1024);
        assertThat(mvc.post().uri("/api/publish/uploads").with(OWNER).with(csrf())
                .multipart().file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[10])))
                .hasStatus(400).bodyJson().extractingPath("$.detail").asString().contains("public URL");
    }
}
