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
import java.util.ArrayList;
import java.util.List;
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
import com.pagelens.core.config.PageLensProperties;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.sync.SyncService;
import com.pagelens.core.write.WriteGuard;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Claude's write tools, with both switches on, over real MCP JSON-RPC. */
@SpringBootTest(properties = {"pagelens.mcp.stdio-enabled=false", "pagelens.write-actions-enabled=true",
        "pagelens.mcp.write-tools-enabled=true", "pagelens.publish.poll-initial=10ms"})
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class PageLensMcpWriteToolsTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final String IG = "17841400000000001";
    static final DateTimeFormatter IG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneOffset.UTC);
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
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

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        jdbc.sql("DELETE FROM audit_log").update();
        stubReadApi();
        long accountId = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
        sync.run(accountId, SyncService.JobType.FULL, SyncService.Trigger.SCHEDULER);
        jdbc.sql("UPDATE ig_token SET scopes = 'instagram_business_content_publish'").update();

        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut, 1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverOut, 1 << 20);
        toServer = new PrintStream(clientOut, true, StandardCharsets.UTF_8);
        fromServer = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
        running = mcp.start(serverIn, serverOut);
        request("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
        toServer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    @AfterEach
    void tearDown() throws Exception {
        toServer.close();
        running.awaitClose();
        running.server().closeGracefully();
    }

    @Test
    void writeToolsAreListedAndMarkedAsWrites() {
        JsonNode tools = request("tools/list", "{}").path("result").path("tools");
        List<String> writes = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            JsonNode t = tools.get(i);
            if (!t.path("annotations").path("readOnlyHint").asBoolean()) {
                writes.add(t.path("name").asString());
            }
        }
        assertThat(writes).containsExactlyInAnyOrder("sync_now", "reply_to_comment", "hide_comment", "publish_post");
        assertThat(tools.size()).isEqualTo(18); // 14 read + 3 writes + get_publish_status
    }

    @Test
    void replyDefaultsToDryRunAndOnlyActsWhenToldTo() {
        long comment = jdbc.sql("SELECT id FROM comment WHERE ig_comment_id = '17900000000000002'").query(Long.class).single();
        fake.onJson("POST", "/v27.0/17900000000000002/replies", "{\"id\":\"17900000000000999\"}");

        JsonNode preview = callTool("reply_to_comment", "{\"comment_id\":" + comment + ",\"message\":\"Merci!\"}");
        assertThat(preview.path("dryRun").asBoolean()).isTrue();
        assertThat(preview.path("next").asString()).contains("dry_run=false");
        assertThat(fake.requests("/v27.0/17900000000000002/replies")).isEmpty();

        JsonNode done = callTool("reply_to_comment", "{\"comment_id\":" + comment + ",\"message\":\"Merci!\",\"dry_run\":false}");
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(fake.requests("/v27.0/17900000000000002/replies")).hasSize(1);
        assertThat(jdbc.sql("SELECT string_agg(actor || ':' || outcome, ',' ORDER BY id) FROM audit_log WHERE action = 'comment.reply'")
                .query(String.class).single()).isEqualTo("mcp:dry_run_ok,mcp:ok");
        // get_post_comments exposes the id Claude needs.
        assertThat(callToolText("get_post_comments", "{\"post_id\":" + jdbc.sql(
                "SELECT media_id FROM comment WHERE id = :id").param("id", comment).query(Long.class).single() + "}"))
                .contains("\"commentId\":" + comment);
    }

    @Test
    void publishPostPreviewsThenPublishesFromPublicUrls() {
        fake.media("/img/p.jpg", "image/jpeg", 5000);
        fake.onJson("GET", "/v27.0/" + IG + "/content_publishing_limit", "{\"data\":[{\"quota_usage\":0,\"config\":{\"quota_total\":50}}]}");
        fake.onJson("POST", "/v27.0/" + IG + "/media", "{\"id\":\"c-mcp\"}");
        fake.onJson("GET", "/v27.0/c-mcp", "{\"status_code\":\"FINISHED\"}");
        fake.onJson("POST", "/v27.0/" + IG + "/media_publish", "{\"id\":\"m-mcp\"}");
        fake.onJson("GET", "/v27.0/m-mcp", "{\"id\":\"m-mcp\",\"media_type\":\"IMAGE\",\"permalink\":\"https://instagram.com/p/mcp\"}");
        String args = "{\"kind\":\"IMAGE\",\"caption\":\"From Claude\",\"media\":[{\"type\":\"IMAGE\",\"url\":\"" + fake.url() + "/img/p.jpg\"}]";

        JsonNode preview = callTool("publish_post", args + "}");
        assertThat(preview.path("ok").asBoolean()).isTrue();
        assertThat(preview.path("dryRun").asBoolean()).isTrue();
        assertThat(fake.requests("/v27.0/" + IG + "/media_publish")).isEmpty();

        JsonNode published = callTool("publish_post", args + ",\"dry_run\":false}");
        assertThat(published.path("job").path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.path("job").path("permalink").asString()).isEqualTo("https://instagram.com/p/mcp");

        // Same content again (e.g. Claude retrying): no second post.
        JsonNode again = callTool("publish_post", args + ",\"dry_run\":false}");
        assertThat(again.path("warnings").toString()).contains("already submitted");
        assertThat(fake.requests("/v27.0/" + IG + "/media_publish")).hasSize(1);

        long jobId = published.path("job").path("jobId").asLong();
        assertThat(callTool("get_publish_status", "{\"job_id\":" + jobId + "}").path("status").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void writeToolsNeedBothSwitches() {
        WriteGuard off = new WriteGuard(new PageLensProperties("logs", "", false, null, null));
        assertThat(new PageLensWriteTools(null, null, null, null, off, new McpProperties(false, true, true)).specifications())
                .isEmpty();
        WriteGuard on = new WriteGuard(new PageLensProperties("logs", "", true, null, null));
        assertThat(new PageLensWriteTools(null, null, null, null, on, new McpProperties(false, true, false)).specifications())
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private JsonNode callTool(String name, String args) {
        return JSON.readTree(callToolText(name, args));
    }

    private String callToolText(String name, String args) {
        JsonNode result = request("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}").path("result");
        assertThat(result.path("isError").asBoolean()).as(name + ": " + result).isFalse();
        return result.path("content").path(0).path("text").asString();
    }

    private JsonNode request(String method, String params) {
        int id = ids.incrementAndGet();
        toServer.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        try {
            while (true) {
                String line = fromServer.readLine();
                assertThat(line).isNotNull();
                JsonNode msg = JSON.readTree(line);
                if (msg.path("id").asInt(-1) == id) {
                    return msg;
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void stubReadApi() {
        Instant now = Instant.now();
        String page1 = fixture("media-page1.json").replace("${BASE}", fake.url())
                .replace("${RECENT_1}", IG_TS.format(now.minus(1, ChronoUnit.DAYS)))
                .replace("${RECENT_2}", IG_TS.format(now.minus(2, ChronoUnit.DAYS)));
        fake.onFixture("GET", "/v27.0/me", "ig-me.json");
        fake.on("GET", "/v27.0/" + IG + "/media", r -> Response.json(r.query().containsKey("after")
                ? fixture("media-page2.json") : page1));
        fake.onFixture("GET", "/v27.0/18000000000000003/comments", "comments.json");
        fake.onJson("GET", "/v27.0/18000000000000002/comments", "{\"data\":[]}");
    }
}
