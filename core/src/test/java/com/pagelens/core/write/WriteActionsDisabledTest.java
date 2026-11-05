package com.pagelens.core.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.write.PublishModels.Item;
import com.pagelens.core.write.PublishModels.ItemType;
import com.pagelens.core.write.PublishModels.Kind;
import com.pagelens.core.write.PublishModels.PublishRequest;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** The default: write actions OFF. Real writes are refused and audited; dry runs explain why. */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class WriteActionsDisabledTest {

    static final FakeGraphApi fake = FakeGraphApi.start();

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
    WriteGuard guard;
    @Autowired
    JdbcClient jdbc;

    @Test
    void writesAreOffByDefault() {
        jdbc.sql("DELETE FROM audit_log").update();
        assertThat(guard.enabled()).isFalse();

        assertThatThrownBy(() -> comments.reply(1, "hi", false, Actor.MCP))
                .isInstanceOf(WriteGuard.WriteActionsDisabledException.class)
                .hasMessageContaining("PAGELENS_WRITE_ACTIONS_ENABLED");
        assertThatThrownBy(() -> publisher.submit(new PublishRequest(1, Kind.IMAGE, null,
                List.of(new Item(ItemType.IMAGE, "https://example.com/a.jpg", null)), null, null), Actor.UI))
                .isInstanceOf(WriteGuard.WriteActionsDisabledException.class);

        WriteResult dry = comments.reply(1, "hi", true, Actor.MCP);
        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).anySatisfy(e -> assertThat(e).contains("disabled"));

        assertThat(fake.requests()).isEmpty(); // nothing reached Instagram
        assertThat(jdbc.sql("SELECT string_agg(outcome, ',' ORDER BY id) FROM audit_log").query(String.class).single())
                .isEqualTo("disabled,disabled,rejected");
    }
}
