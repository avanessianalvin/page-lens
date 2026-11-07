package com.pagelens.core.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

import com.pagelens.core.importer.BusinessSuiteImporter.ImportException;
import com.pagelens.core.importer.BusinessSuiteImporter.Report;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

@SpringBootTest
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class BusinessSuiteImporterTest {

    static final FakeGraphApi fake = FakeGraphApi.start();
    static final String ACC = "17841400000000055";

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
    BusinessSuiteImporter importer;
    @Autowired
    AccountRepository accounts;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM ig_account").update();
    }

    static byte[] fixture() {
        try (InputStream in = BusinessSuiteImporterTest.class.getResourceAsStream("/fixtures/import/business-suite-posts.csv")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void importsPostsAndSnapshotsAndCreatesTheAccount() {
        Report report = importer.importCsv(fixture(), null, Actor.UI);

        assertThat(report.rows()).isEqualTo(4);
        assertThat(report.posts()).isEqualTo(3);
        assertThat(report.snapshots()).isEqualTo(3);
        assertThat(report.accounts()).containsExactly("@shop_am");
        assertThat(report.skipped()).containsExactly("row 5: no Post ID");
        assertThat(report.ignoredColumns()).contains("Date", "Data comment");

        var account = accounts.findByIgUserId(ACC).orElseThrow();
        assertThat(account.loginType()).isEqualTo(LoginType.IMPORT);
        assertThat(account.username()).isEqualTo("shop_am");

        assertThat(jdbc.sql("SELECT caption FROM media WHERE ig_media_id = '18000000000000101'").query(String.class).single())
                .isEqualTo("Նոր հավաքածու, autumn \"drop\"\nline two");
        assertThat(jdbc.sql("SELECT posted_at = TIMESTAMPTZ '2026-11-01 08:00:00+00' FROM media WHERE ig_media_id = '18000000000000101'")
                .query(Boolean.class).single()).isTrue(); // 12:00 Yerevan
        assertThat(jdbc.sql("SELECT views FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = '18000000000000101'").query(Long.class).single()).isEqualTo(1234L);
        assertThat(jdbc.sql("SELECT media_type || '/' || media_product_type FROM media WHERE ig_media_id = '18000000000000102'")
                .query(String.class).single()).isEqualTo("VIDEO/REELS");
        assertThat(jdbc.sql("SELECT extra_metrics FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = '18000000000000102'").query(String.class).single()).contains("\"duration_sec\":15");
        assertThat(jdbc.sql("SELECT is_story FROM media WHERE ig_media_id = '18000000000000103'").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT likes IS NULL FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = '18000000000000103'").query(Boolean.class).single()).isTrue(); // empty, not 0
        assertThat(jdbc.sql("SELECT extra_metrics FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id "
                + "WHERE m.ig_media_id = '18000000000000103'").query(String.class).single()).contains("\"sticker_taps\":6");

        importer.importCsv(fixture(), null, Actor.UI);
        assertThat(jdbc.sql("SELECT count(*) FROM media").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void keepsAnExistingDirectConnection() {
        accounts.upsertConnected(ACC, LoginType.FACEBOOK_LOGIN, "page-1", "shop_am");

        importer.importCsv(fixture(), null, Actor.UI);

        assertThat(accounts.findByIgUserId(ACC).orElseThrow().loginType()).isEqualTo(LoginType.FACEBOOK_LOGIN);
    }

    @Test
    void withoutAccountIdColumnTheCallerChoosesTheAccount() {
        String csv = "Post ID,Publish time,Post type,Reach\n1801,11/01/2026 12:00,IG image,10\n";
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> importer.importCsv(bytes, null, Actor.UI))
                .isInstanceOf(ImportException.class).hasMessageContaining("Account ID");

        long id = accounts.upsertReadOnlySource("17841400000000066", LoginType.IMPORT, "chosen");
        Report report = importer.importCsv(bytes, id, Actor.UI);
        assertThat(report.posts()).isEqualTo(1);
        assertThat(report.accounts()).containsExactly("@chosen");
    }

    @Test
    void rejectsFilesThatAreNotContentExports() {
        byte[] other = "Date,Followers\n2026-11-01,10\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> importer.importCsv(other, null, Actor.UI))
                .isInstanceOf(ImportException.class).hasMessageContaining("Post ID").hasMessageContaining("Followers");
        assertThatThrownBy(() -> importer.importCsv(new byte[0], null, Actor.UI)).isInstanceOf(ImportException.class);
    }
}
