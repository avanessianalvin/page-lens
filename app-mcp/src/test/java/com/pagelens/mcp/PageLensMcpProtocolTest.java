package com.pagelens.mcp;

import static com.pagelens.testsupport.FakeGraphApi.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import com.pagelens.core.sync.SyncService;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Speaks real MCP JSON-RPC to the server over pipes (the same transport code as stdio), against data synced from
 * a fake Graph API into PostgreSQL.
 */
@SpringBootTest(properties = "pagelens.mcp.stdio-enabled=false")
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class PageLensMcpProtocolTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final String IG = "17841400000000001";
    static final DateTimeFormatter IG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
        r.add("pagelens.time-zone", () -> "Asia/Yerevan");
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired
    PageLensMcpServer mcp;
    @Autowired
    AccountConnectionService connections;
    @Autowired
    SyncService sync;
    @Autowired
    JdbcClient jdbc;

    PrintStream toServer;
    BufferedReader fromServer;
    PageLensMcpServer.Running running;
    final AtomicInteger ids = new AtomicInteger();
    long accountId;

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        jdbc.sql("DELETE FROM sync_run").update();
        jdbc.sql("DELETE FROM audit_log").update();
        stubInstagramAccount();
        accountId = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        sync.run(accountId, SyncService.JobType.FULL, SyncService.Trigger.SCHEDULER);

        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut, 1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 20);
        toServer = new PrintStream(clientOut, true, StandardCharsets.UTF_8);
        fromServer = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
        running = mcp.start(serverIn, serverOut);

        JsonNode init = request("initialize", """
                {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""");
        assertThat(init.path("result").path("serverInfo").path("name").asString()).isEqualTo("pagelens");
        assertThat(init.path("result").path("instructions").asString()).contains("never zero");
        toServer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    @AfterEach
    void tearDown() throws Exception {
        toServer.close(); // stdin EOF: the server must notice
        running.awaitClose();
        running.server().closeGracefully();
    }

    @Test
    void listsAllPlannedToolsWithReadOnlyAnnotations() {
        JsonNode tools = request("tools/list", "{}").path("result").path("tools");
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            names.add(tools.get(i).path("name").asString());
            if (!tools.get(i).path("name").asString().equals("sync_now")) {
                assertThat(tools.get(i).path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            }
            assertThat(tools.get(i).path("description").asString()).hasSizeGreaterThan(40);
        }
        assertThat(names).containsExactlyInAnyOrder("list_accounts", "get_account_overview", "list_posts",
                "get_post_details", "get_post_comments", "get_top_posts", "get_bottom_posts", "compare_formats",
                "get_best_posting_times", "get_audience_breakdown", "get_follower_growth", "get_caption_stats",
                "get_sync_status", "sync_now");
    }

    @Test
    void readToolsAnswerFromLocalData() {
        JsonNode accounts = callTool("list_accounts", "{}");
        assertThat(accounts.path("timeZone").asString()).isEqualTo("Asia/Yerevan");
        assertThat(accounts.path("accounts").path(0).path("username").asString()).isEqualTo("pagelens.test");

        int callsBefore = fake.requests().size();

        JsonNode overview = callTool("get_account_overview", "{\"account\":\"@pagelens.test\"}");
        assertThat(overview.path("followers").asLong()).isEqualTo(1250);
        assertThat(overview.path("bestRecentByReach").path(0).path("metrics").path("reach").asLong()).isEqualTo(900);

        JsonNode top = callTool("get_top_posts", "{\"metric\":\"reach\",\"count\":2,\"period\":\"all\"}");
        assertThat(top.path("posts").size()).isEqualTo(2);
        assertThat(top.path("posts").path(0).path("format").asString()).isEqualTo("REELS");
        // Times are rendered in the owner's zone.
        assertThat(top.path("posts").path(0).path("postedAt").asString()).endsWith("+04:00");
        // Farsi caption survives the round trip.
        assertThat(top.path("posts").path(0).path("caption").asString()).startsWith("سلام");

        JsonNode posts = callTool("list_posts", "{\"sort_by\":\"reach\",\"limit\":5}");
        assertThat(posts.path("total").asInt()).isEqualTo(3);
        long reelId = posts.path("posts").path(0).path("postId").asLong();

        JsonNode details = callTool("get_post_details", "{\"post_id\":" + reelId + "}");
        assertThat(details.path("latest").path("avgWatchTimeMs").asLong()).isEqualTo(7400);
        assertThat(details.path("snapshotCount").asInt()).isEqualTo(1);

        JsonNode comments = callTool("get_post_comments", "{\"post_id\":" + reelId + "}");
        assertThat(comments.path("threads").path(0).path("replies").path(0).path("username").asString())
                .isEqualTo("pagelens.test");

        JsonNode formats = callTool("compare_formats", "{\"period\":\"all\"}");
        assertThat(formats.path("formats").size()).isEqualTo(3);
        assertThat(formats.path("notes").asString()).contains("interactions / reach");

        assertThat(callTool("get_best_posting_times", "{\"period\":\"all\"}").path("timeZone").asString())
                .isEqualTo("Asia/Yerevan");
        JsonNode audience = callTool("get_audience_breakdown", "{\"dimension\":\"country\",\"limit\":3}");
        assertThat(audience.path("breakdowns").path(0).path("groups").size()).isEqualTo(3);
        assertThat(callTool("get_follower_growth", "{}").path("endFollowers").asLong()).isEqualTo(1250);
        assertThat(callTool("get_follower_growth", "{\"mode\":\"per_post\",\"period\":\"all\"}").path("perPost").size())
                .isEqualTo(3);
        assertThat(callTool("get_caption_stats", "{\"period\":\"all\"}").path("buckets").size()).isGreaterThan(3);
        assertThat(callTool("get_sync_status", "{}").path("runs").path(0).path("status").asString()).isEqualTo("SUCCEEDED");

        // None of the read tools called Instagram.
        assertThat(fake.requests()).hasSize(callsBefore);
        // Missing values are omitted, never printed as 0 or null.
        assertThat(callToolText("list_posts", "{}")).doesNotContain("null");
    }

    @Test
    void badInputIsAToolErrorClaudeCanRead() {
        JsonNode result = request("tools/call", "{\"name\":\"get_top_posts\",\"arguments\":{\"metric\":\"impressions\"}}")
                .path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        // The SDK validates against the tool's JSON schema first; its message lists the allowed values.
        assertThat(result.path("content").path(0).path("text").asString())
                .contains("metric").contains("engagement_rate");

        JsonNode wrongAccount = request("tools/call", "{\"name\":\"list_posts\",\"arguments\":{\"account\":\"@nobody\"}}")
                .path("result");
        assertThat(wrongAccount.path("content").path(0).path("text").asString()).contains("pagelens.test");
    }

    @Test
    void syncNowStartsARunAndIsAudited() throws Exception {
        JsonNode started = callTool("sync_now", "{\"job\":\"STORIES\"}");
        assertThat(started.path("started").asBoolean()).isTrue();
        long runId = started.path("runId").asLong();
        for (int i = 0; i < 50 && "RUNNING".equals(status(runId)); i++) {
            Thread.sleep(100);
        }
        assertThat(jdbc.sql("SELECT triggered_by FROM sync_run WHERE id = :id").param("id", runId)
                .query(String.class).single()).isEqualTo("mcp");
        assertThat(jdbc.sql("SELECT actor || ':' || action FROM audit_log WHERE action = 'sync.start'")
                .query(String.class).single()).isEqualTo("mcp:sync.start");
    }

    @Test
    void glossaryResourceAndWeeklyPrompt() {
        JsonNode glossary = request("resources/read", "{\"uri\":\"pagelens://glossary/metrics\"}");
        assertThat(glossary.path("result").path("contents").path(0).path("text").asString())
                .contains("Missing is not zero");
        JsonNode accounts = request("resources/read", "{\"uri\":\"pagelens://accounts\"}");
        assertThat(accounts.path("result").path("contents").path(0).path("text").asString()).contains("pagelens.test");

        JsonNode prompt = request("prompts/get", "{\"name\":\"weekly_performance_review\",\"arguments\":{\"account\":\"@pagelens.test\"}}");
        assertThat(prompt.path("result").path("messages").path(0).path("content").path("text").asString())
                .contains("@pagelens.test").contains("get_top_posts");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private String status(long runId) {
        return jdbc.sql("SELECT status FROM sync_run WHERE id = :id").param("id", runId).query(String.class).single();
    }

    private JsonNode callTool(String name, String args) {
        return JSON.readTree(callToolText(name, args));
    }

    private String callToolText(String name, String args) {
        JsonNode result = request("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}").path("result");
        assertThat(result.path("isError").asBoolean()).as(name + ": " + result).isFalse();
        return result.path("content").path(0).path("text").asString();
    }

    @Test
    void answersARequestSentRightBeforeTheClientCloses() {
        int id = ids.incrementAndGet();
        toServer.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"list_accounts\",\"arguments\":{}}}");
        toServer.close(); // EOF immediately after the request
        JsonNode response = readResponse(id, "tools/call");
        assertThat(response.path("result").path("content").path(0).path("text").asString()).contains("pagelens.test");
    }

    /** Sends one request and returns the response with the matching id (skipping notifications). */
    private JsonNode request(String method, String params) {
        int id = ids.incrementAndGet();
        toServer.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        return readResponse(id, method);
    }

    private JsonNode readResponse(int id, String method) {
        try {
            while (true) {
                String line = fromServer.readLine();
                assertThat(line).as("server closed the stream").isNotNull();
                JsonNode msg = JSON.readTree(line); // every stdout line must be a JSON-RPC message
                assertThat(msg.path("jsonrpc").asString()).isEqualTo("2.0");
                if (msg.path("id").asInt(-1) == id) {
                    assertThat(msg.has("error")).as(method + " error: " + msg).isFalse();
                    return msg;
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
        fake.onJson("GET", "/v27.0/" + IG + "/stories", "{\"data\":[]}");
        fake.on("GET", "/v27.0/" + IG + "/insights", r -> switch (r.query().get("metric")) {
            case "follows_and_unfollows" -> Response.json(fixture("follows-and-unfollows.json"));
            case "follower_demographics" -> Response.json(fixture("demographics-country.json"));
            default -> Response.json(fixture("account-day.json"));
        });
    }
}
