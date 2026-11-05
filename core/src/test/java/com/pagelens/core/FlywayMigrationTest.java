package com.pagelens.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pagelens.testsupport.PostgresTestcontainer;

@SpringBootTest
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void baselineCreatesAllCoreTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'pagelens'", String.class);

        assertThat(tables).contains("flyway_schema_history",
                "ig_account", "ig_token", "media", "media_insight_snapshot", "comment",
                "account_daily_metric", "audience_snapshot", "sync_run", "api_call_log", "audit_log");

        // Nothing from PageLens may leak into the shared database's public schema.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'", Integer.class))
                .isZero();
    }

    @Test
    void mediaUpsertOnNaturalKeyIsIdempotent() {
        jdbc.update("INSERT INTO ig_account (ig_user_id, login_type, username) VALUES ('u1', 'INSTAGRAM_LOGIN', 'me')");
        Long accountId = jdbc.queryForObject("SELECT id FROM ig_account WHERE ig_user_id = 'u1'", Long.class);

        String upsert = """
                INSERT INTO media (account_id, ig_media_id, media_type, caption) VALUES (?, 'm1', 'IMAGE', ?)
                ON CONFLICT (ig_media_id) DO UPDATE SET caption = EXCLUDED.caption
                """;
        jdbc.update(upsert, accountId, "first");
        jdbc.update(upsert, accountId, "سلام Բարեւ"); // Farsi + Armenian must round-trip

        assertThat(jdbc.queryForObject("SELECT count(*) FROM media WHERE ig_media_id = 'm1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT caption FROM media WHERE ig_media_id = 'm1'", String.class))
                .isEqualTo("سلام Բարեւ");
    }
}
