package com.pagelens.core.windsor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WindsorMapperTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode row(String json) {
        return JSON.readTree(json);
    }

    @Test
    void numbersAreNullWhenMissingAndToleratePlainStrings() {
        JsonNode r = row("{\"a\":12,\"b\":\"1,234\",\"c\":null,\"d\":\"\",\"e\":\"n/a\",\"f\":12.6}");
        assertThat(WindsorMapper.number(r, "a")).isEqualTo(12L);
        assertThat(WindsorMapper.number(r, "b")).isEqualTo(1234L);
        assertThat(WindsorMapper.number(r, "c")).isNull();
        assertThat(WindsorMapper.number(r, "d")).isNull();
        assertThat(WindsorMapper.number(r, "e")).isNull();
        assertThat(WindsorMapper.number(r, "f")).isEqualTo(13L);
        assertThat(WindsorMapper.number(r, "missing")).isNull();
    }

    @Test
    void readsTheTimestampShapesWindsorAndMetaUse() {
        Instant t = Instant.parse("2026-11-01T08:00:00Z");
        assertThat(WindsorMapper.instant("2026-11-01T08:00:00+0000")).isEqualTo(t);
        assertThat(WindsorMapper.instant("2026-11-01T12:00:00+04:00")).isEqualTo(t);
        assertThat(WindsorMapper.instant("2026-11-01 08:00:00")).isEqualTo(t);
        assertThat(WindsorMapper.instant("2026-11-01T08:00:00")).isEqualTo(t);
        assertThat(WindsorMapper.instant("2026-11-01")).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(WindsorMapper.instant("yesterday")).isNull();
        assertThat(WindsorMapper.instant(null)).isNull();
    }

    @Test
    void reelsBecomeVideoWithProductReels() {
        var m = WindsorMapper.media(row("{\"media_id\":\"1\",\"media_type\":\"REEL\",\"media_product_type\":\"FEED\","
                + "\"media_views\":10,\"media_engagement\":3}"));
        assertThat(m.media().mediaType()).isEqualTo("VIDEO");
        assertThat(m.media().effectiveProductType()).isEqualTo("REELS");
        assertThat(m.metrics()).containsEntry("views", 10L).containsEntry("total_interactions", 3L)
                .doesNotContainKey("reach");
    }

    @Test
    void commentRowsBecomeThreadsWithReplies() {
        var threads = WindsorMapper.commentThreads(List.of(
                row("{\"media_id\":\"m\",\"comment_id\":\"r1\",\"comment_parent_id\":\"t1\"}"),
                row("{\"media_id\":\"m\",\"comment_id\":\"t1\"}"),
                row("{\"media_id\":\"m\",\"comment_id\":\"t1\"}"), // duplicate row
                row("{\"media_id\":\"m\",\"comment_id\":\"orphan\",\"comment_parent_id\":\"gone\"}")));
        assertThat(threads.get("m")).extracting(c -> c.id()).containsExactly("t1", "orphan");
        assertThat(threads.get("m").getFirst().replies()).extracting(c -> c.id()).containsExactly("r1");
    }

    @Test
    void audienceSumsRowsPerKey() {
        var values = WindsorMapper.audience(List.of(
                row("{\"audience_age_name\":\"18-24\",\"audience_age_size\":5}"),
                row("{\"audience_age_name\":\"18-24\",\"audience_age_size\":2}"),
                row("{\"audience_age_name\":\"25-34\",\"audience_age_size\":null}")), "audience_age_name", "audience_age_size");
        assertThat(values).containsExactly(java.util.Map.entry("18-24", 7L));
    }

    @Test
    void rowsWithoutAccountIdAreKeptForSingleAccountWorkspaces() {
        assertThat(WindsorMapper.belongsTo(row("{}"), "1")).isTrue();
        assertThat(WindsorMapper.belongsTo(row("{\"account_id\":\"2\"}"), "1")).isFalse();
    }
}
