package com.pagelens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** Windsor.ai discovery and CSV import endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class PageLensSourcesApiTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final RequestPostProcessor OWNER = user("admin").roles("OWNER");
    static final String KEY = "TEST-WINDSOR-KEY-123";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
        String hash = new BCryptPasswordEncoder(4).encode("irrelevant-password");
        r.add("pagelens.web.password-hash", () -> hash);
        r.add("pagelens.windsor.base-url", fake::url);
        r.add("pagelens.windsor.api-key", () -> KEY);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
    }

    @Test
    void reportsWhetherWindsorIsConfigured() {
        assertThat(mvc.get().uri("/api/sources").with(OWNER)).hasStatusOk().bodyJson()
                .extractingPath("$.windsorConfigured").isEqualTo(true);
        assertThat(mvc.get().uri("/api/sources")).hasStatus(401);
    }

    @Test
    void discoversWindsorAccounts() {
        fake.onJson("GET", "/instagram", """
                {"data":[{"account_id":"17841400000000077","username":"pagelens_test","followers_count":5}]}""");

        assertThat(mvc.post().uri("/api/sources/windsor/accounts").with(OWNER).with(csrf())).hasStatusOk()
                .bodyJson().extractingPath("$[0].loginType").isEqualTo("WINDSOR");
        assertThat(jdbc.sql("SELECT login_type FROM ig_account").query(String.class).single()).isEqualTo("WINDSOR");
    }

    @Test
    void windsorKeyProblemsBecomeA422WithoutTheKey() {
        fake.on("GET", "/instagram", r -> new Response(401, "{\"error\":\"Not authorized\"}", Map.of()));

        assertThat(mvc.post().uri("/api/sources/windsor/accounts").with(OWNER).with(csrf())).hasStatus(422)
                .bodyJson().extractingPath("$.detail").asString().contains("API key").doesNotContain(KEY);
    }

    @Test
    void importsABusinessSuiteCsv() {
        byte[] csv = """
                Post ID,Account ID,Account username,Publish time,Post type,Reach,Likes
                18000000000000201,17841400000000055,shop_am,11/01/2026 12:00,IG image,"1,500",30
                """.getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv", csv);

        assertThat(mvc.post().uri("/api/sources/import/business-suite").multipart().file(file).with(OWNER).with(csrf()))
                .hasStatusOk().bodyJson().extractingPath("$.posts").isEqualTo(1);
        assertThat(jdbc.sql("SELECT reach FROM media_insight_snapshot").query(Long.class).single()).isEqualTo(1500L);

        MockMultipartFile wrong = new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        assertThat(mvc.post().uri("/api/sources/import/business-suite").multipart().file(wrong).with(OWNER).with(csrf()))
                .hasStatus(400).bodyJson().extractingPath("$.detail").asString().contains("Post ID");
    }
}
