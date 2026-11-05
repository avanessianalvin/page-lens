package com.pagelens;

import static com.pagelens.testsupport.FakeGraphApi.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.mock.web.MockHttpSession;
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
import com.pagelens.web.CorrelationIdFilter;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class PageLensWebApplicationTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final String PASSWORD = "correct-horse-battery";
    static final String IG = "17841400000000001";
    static final DateTimeFormatter IG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);
    static final RequestPostProcessor OWNER = user("admin").roles("OWNER");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
        String hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
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

    @BeforeEach
    void reset() {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        jdbc.sql("DELETE FROM api_call_log").update();
        jdbc.sql("DELETE FROM sync_run").update();
    }

    // ------------------------------------------------------------------------------------------ security

    @Test
    void healthIsPublicButDetailsAreNot() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(mvc.get().uri("/actuator/health")).bodyJson().doesNotHavePath("$.components");
        assertThat(mvc.get().uri("/actuator/health").with(OWNER))
                .bodyJson().extractingPath("$.components.db.status").isEqualTo("UP");
    }

    @Test
    void apiRequiresLoginAndAnswers401InsteadOfRedirecting() {
        assertThat(mvc.get().uri("/api/accounts")).hasStatus(401);
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatus(401);
    }

    @Test
    void formLoginWithCsrfCreatesSession() {
        var session = new MockHttpSession();
        assertThat(mvc.post().uri("/api/auth/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "admin").param("password", PASSWORD))
                .hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo("admin");
        assertThat(mvc.get().uri("/api/accounts").session(session)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/session").session(session))
                .bodyJson().extractingPath("$.authenticated").isEqualTo(true);
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() {
        assertThat(mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "admin").param("password", PASSWORD)).hasStatus(403);
    }

    @Test
    void repeatedLoginFailuresLockTheClientOut() {
        for (int i = 0; i < 5; i++) {
            assertThat(login("wrong-password", "10.0.0.5")).hasStatus(401);
        }
        assertThat(login(PASSWORD, "10.0.0.5")).hasStatus(429); // even the right password, while locked
        assertThat(login(PASSWORD, "10.0.0.6")).hasStatusOk(); // other clients unaffected
    }

    @Test
    void correlationIdHeaderIsGeneratedOrSafelyReused() {
        assertThat(mvc.get().uri("/actuator/health")).headers().hasHeaderSatisfying(CorrelationIdFilter.HEADER,
                v -> assertThat(v.getFirst()).startsWith("http-"));
        assertThat(mvc.get().uri("/actuator/health").header(CorrelationIdFilter.HEADER, "ui-abc123"))
                .headers().hasValue(CorrelationIdFilter.HEADER, "ui-abc123");
        assertThat(mvc.get().uri("/actuator/health").header(CorrelationIdFilter.HEADER, "bad\nvalue"))
                .headers().hasHeaderSatisfying(CorrelationIdFilter.HEADER, v -> assertThat(v.getFirst()).startsWith("http-"));
    }

    @Test
    void spaRoutesAreServedWithoutLogin() {
        assertThat(mvc.get().uri("/accounts")).hasStatusOk();
        assertThat(mvc.get().uri("/posts/12")).hasStatusOk();
    }

    // ------------------------------------------------------------------------------------------ OAuth

    @Test
    void oauthRoundTripValidatesStateAndConnects() throws Exception {
        var session = new MockHttpSession();
        var result = mvc.post().uri("/api/accounts/connect").session(session).with(OWNER).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"loginType\":\"INSTAGRAM_LOGIN\"}").exchange();
        assertThat(result).hasStatusOk();
        String url = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.authorizationUrl");
        String state = FakeGraphApi.parse(url.substring(url.indexOf('?') + 1)).get("state");
        assertThat(state).hasSizeGreaterThan(20);

        // Forged state: rejected.
        assertThat(mvc.get().uri("/auth/callback?code=abc&state=forged").session(session).with(OWNER))
                .hasStatus3xxRedirection().redirectedUrl().startsWith("/accounts?error=");

        // Real round trip.
        mvc.post().uri("/api/accounts/connect").session(session).with(OWNER).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"loginType\":\"INSTAGRAM_LOGIN\"}").exchange();
        String fresh = (String) session.getAttribute("pagelens.oauth.state");
        fake.onFixture("POST", "/oauth/access_token", "oauth-instagram-code.json");
        fake.onFixture("GET", "/access_token", "oauth-long-lived.json");
        stubInstagramAccount();

        assertThat(mvc.get().uri("/auth/callback?code=AQBcode%23_&state=" + fresh).session(session).with(OWNER))
                .hasStatus3xxRedirection().redirectedUrl().isEqualTo("/accounts?connected=1");
        assertThat(jdbc.sql("SELECT username FROM ig_account").query(String.class).single()).isEqualTo("pagelens.test");
        assertThat(session.getAttribute("pagelens.oauth.state")).isNull(); // single use
    }

    @Test
    void tokenPasteConnectNeverEchoesTheToken() {
        stubInstagramAccount();
        var result = mvc.post().uri("/api/accounts/connect-token").with(OWNER).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginType\":\"INSTAGRAM_LOGIN\",\"accessToken\":\"TEST-TOKEN-123\"}").exchange();
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$[0].account.username").isEqualTo("pagelens.test");
        assertThat(result).body().asString().doesNotContain("TEST-TOKEN-123");
    }

    // ------------------------------------------------------------------------------------------ read API

    @Test
    void readEndpointsServeSyncedData() throws Exception {
        stubInstagramAccount();
        long id = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        sync.run(id, SyncService.JobType.FULL, SyncService.Trigger.UI);

        assertThat(mvc.get().uri("/api/accounts").with(OWNER)).hasStatusOk().bodyJson()
                .extractingPath("$[0].token.status").isEqualTo("VALID");

        var posts = mvc.get().uri("/api/accounts/" + id + "/posts?sort=reach&dir=desc").with(OWNER).exchange();
        assertThat(posts).hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(3);
        assertThat(posts).bodyJson().extractingPath("$.items[0].latest.reach").isEqualTo(900);
        assertThat(posts).bodyJson().extractingPath("$.items[0].postedAt").asString().endsWith("Z");

        assertThat(mvc.get().uri("/api/accounts/" + id + "/posts?type=REELS").with(OWNER))
                .bodyJson().extractingPath("$.total").isEqualTo(1);
        assertThat(mvc.get().uri("/api/accounts/" + id + "/posts?sort=bogus").with(OWNER)).hasStatus(400);

        long reelId = jdbc.sql("SELECT id FROM media WHERE ig_media_id = '18000000000000003'").query(Long.class).single();
        assertThat(mvc.get().uri("/api/posts/" + reelId).with(OWNER)).hasStatusOk().bodyJson()
                .extractingPath("$.history.length()").isEqualTo(1);
        assertThat(mvc.get().uri("/api/posts/" + reelId + "/comments").with(OWNER)).hasStatusOk().bodyJson()
                .extractingPath("$[0].replies[0].username").isEqualTo("pagelens.test");

        assertThat(mvc.get().uri("/api/accounts/" + id + "/insights/daily").with(OWNER)).hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(4);
        assertThat(mvc.get().uri("/api/accounts/" + id + "/audience?dimension=country").with(OWNER)).hasStatusOk()
                .bodyJson().extractingPath("$[0].entries[3].smallSample").isEqualTo(true); // FR: 12 < 30

        assertThat(mvc.get().uri("/api/sync-runs").with(OWNER)).bodyJson()
                .extractingPath("$[0].status").isEqualTo("SUCCEEDED");
        var calls = mvc.get().uri("/api/logs/api-calls?limit=500").with(OWNER).exchange();
        assertThat(calls).hasStatusOk();
        assertThat(calls).body().asString().contains("access_token=***").doesNotContain("TEST-TOKEN-123");
        assertThat(mvc.get().uri("/api/logs/audit").with(OWNER)).bodyJson()
                .extractingPath("$[0].action").isEqualTo("account.connect");
        assertThat(mvc.get().uri("/api/logs/app?limit=5").with(OWNER)).hasStatusOk();
        assertThat(mvc.get().uri("/api/posts/999999").with(OWNER)).hasStatus(404);
    }

    @Test
    void syncNowReturnsRunIdAndRejectsConcurrentRun() {
        stubInstagramAccount();
        long id = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        jdbc.sql("INSERT INTO sync_run (account_id, job_type, triggered_by, status) VALUES (:id, 'FULL', 'mcp', 'RUNNING')")
                .param("id", id).update();
        assertThat(mvc.post().uri("/api/accounts/" + id + "/sync").with(OWNER).with(csrf())).hasStatus(409);
    }

    @Test
    void disconnectDeletesAccount() {
        stubInstagramAccount();
        long id = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        assertThat(mvc.delete().uri("/api/accounts/" + id).with(OWNER).with(csrf())).hasStatus(204);
        assertThat(mvc.delete().uri("/api/accounts/" + id).with(OWNER).with(csrf())).hasStatus(404);
    }

    /** Writes the OpenAPI spec for the frontend's typed client (frontend: npm run gen:api). */
    @Test
    void exportsOpenApiSpec() throws Exception {
        var result = mvc.get().uri("/v3/api-docs").with(OWNER).exchange();
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.paths['/api/accounts/{id}/posts']").isNotNull();
        Path out = Path.of("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.assertj.MvcTestResult login(String password, String remoteAddr) {
        return mvc.post().uri("/api/auth/login").with(csrf()).with(r -> {
                    r.setRemoteAddr(remoteAddr);
                    return r;
                })
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "admin").param("password", password).exchange();
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
}
