package com.pagelens.core.instagram;

import static com.pagelens.testsupport.FakeGraphApi.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pagelens.core.instagram.IgModels.IgComment;
import com.pagelens.core.instagram.IgModels.IgMedia;
import com.pagelens.core.instagram.IgModels.Insights;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;

class InstagramApiTest {

    FakeGraphApi fake;
    GraphTestKit kit;

    @BeforeEach
    void setUp() {
        fake = FakeGraphApi.start();
        kit = new GraphTestKit(fake);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void instagramLoginProfileUsesUserIdNotAppScopedId() {
        fake.onFixture("GET", "/v27.0/me", "ig-me.json");
        var p = kit.api.profile(GraphTestKit.igAccount());

        assertThat(p.igUserId()).isEqualTo("17841400000000001");
        assertThat(p.followersCount()).isEqualTo(1250L);
        assertThat(fake.requests("/v27.0/me").getFirst().query().get("fields")).startsWith("user_id,");
    }

    @Test
    void mediaFollowsPagingAndParsesInstagramTimestamps() {
        fake.on("GET", "/v27.0/17841400000000001/media", r -> r.query().containsKey("after")
                ? Response.json(fixture("media-page2.json"))
                : Response.json(fixture("media-page1.json").replace("${BASE}", fake.url())
                        .replace("${RECENT_1}", "2026-11-04T10:00:00+0000").replace("${RECENT_2}", "2026-11-03T08:00:00+0000")));

        List<IgMedia> media = kit.api.media(GraphTestKit.igAccount(), 0, m -> true);

        assertThat(media).extracting(IgMedia::id)
                .containsExactly("18000000000000003", "18000000000000002", "18000000000000001");
        assertThat(media.getFirst().timestamp()).isEqualTo(Instant.parse("2026-11-04T10:00:00Z"));
        assertThat(media.getFirst().caption()).startsWith("سلام");
        // Last item has no media_product_type (Instagram Login may omit it): inferred from media_type.
        assertThat(media.get(2).effectiveProductType()).isEqualTo("FEED");
        assertThat(media.get(2).likeCount()).isNull(); // hidden likes stay null, never 0
        // The paging.next URL carries a token; it must be masked in the call log.
        assertThat(kit.recorder.calls).allSatisfy(c -> assertThat(c.endpoint()).doesNotContain("SECRET-IN-NEXT-URL"));
    }

    @Test
    void mediaStopsWhenCallerSaysSo() {
        fake.onJson("GET", "/v27.0/17841400000000001/media", fixture("media-page1.json").replace("${BASE}", fake.url())
                .replace("${RECENT_1}", "2026-11-04T10:00:00+0000").replace("${RECENT_2}", "2026-11-03T08:00:00+0000"));
        List<IgMedia> media = kit.api.media(GraphTestKit.igAccount(), 0, m -> false);
        assertThat(media).hasSize(1);
        assertThat(fake.requests()).hasSize(1);
    }

    @Test
    void rejectedFieldsFallBackToMinimalSet() {
        fake.on("GET", "/v27.0/17841400000000001/media", r -> r.query().get("fields").contains("media_product_type")
                ? Response.error(400, 100, "Tried accessing nonexisting field (media_product_type)")
                : Response.json(fixture("media-page2.json")));
        assertThat(kit.api.media(GraphTestKit.igAccount(), 0, m -> true)).hasSize(1);
    }

    @Test
    void insightsParseValuesAndTotalValueShapes() {
        fake.onFixture("GET", "/v27.0/18000000000000003/insights", "media-insights-reel.json");
        Insights reel = kit.api.mediaInsights(GraphTestKit.igAccount(), "18000000000000003",
                List.of("reach", "views", "saved", "ig_reels_avg_watch_time"));
        assertThat(reel.values()).containsEntry("reach", 900L).containsEntry("saved", 30L)
                .containsEntry("ig_reels_avg_watch_time", 7400L);

        fake.onFixture("GET", "/v27.0/17841400000000001/insights", "account-day.json");
        Insights day = kit.api.accountDay(GraphTestKit.igAccount(), LocalDate.of(2026, 11, 4), List.of("reach"));
        assertThat(day.values()).containsEntry("reach", 2240L).containsEntry("profile_links_taps", 7L);
        var q = fake.requests("/v27.0/17841400000000001/insights").getFirst().query();
        assertThat(q).containsEntry("period", "day").containsEntry("metric_type", "total_value")
                .containsEntry("since", "1793750400").containsEntry("until", "1793836800"); // 2026-11-04/05 00:00Z
    }

    @Test
    void oneRejectedMetricIsRetriedIndividuallyAndReported() {
        fake.on("GET", "/v27.0/55/insights", r -> {
            String metric = r.query().get("metric");
            if (metric.contains("impressions")) {
                return Response.error(400, 100, "(#100) metric[0] must be one of the following values: reach, ...");
            }
            return Response.json("{\"data\":[{\"name\":\"" + metric + "\",\"period\":\"lifetime\",\"values\":[{\"value\":5}]}]}");
        });

        Insights r = kit.api.mediaInsights(GraphTestKit.igAccount(), "55", List.of("reach", "impressions", "likes"));

        assertThat(r.values()).containsOnlyKeys("reach", "likes");
        assertThat(r.unsupported()).containsExactly("impressions");
        assertThat(r.values()).doesNotContainKey("impressions"); // never stored as zero
    }

    @Test
    void commentsIncludeNestedReplies() {
        fake.onFixture("GET", "/v27.0/18000000000000003/comments", "comments.json");
        List<IgComment> threads = kit.api.comments(GraphTestKit.igAccount(), "18000000000000003");

        assertThat(threads).hasSize(2);
        assertThat(threads.getFirst().text()).isEqualTo("عالی بود!");
        assertThat(threads.getFirst().replies()).singleElement()
                .satisfies(reply -> assertThat(reply.username()).isEqualTo("pagelens.test"));
        assertThat(fake.requests().getFirst().query().get("fields")).contains("replies{");
    }

    @Test
    void breakdownsAreFlattened() {
        fake.onFixture("GET", "/v27.0/17841400000000001/insights", "demographics-country.json");
        var countries = kit.api.demographics(GraphTestKit.igAccount(), "follower_demographics", "this_month", "country");
        assertThat(countries).containsEntry("AM", 610L).containsEntry("FR", 12L).hasSize(4);
        assertThat(fake.requests().getFirst().query()).containsEntry("timeframe", "this_month")
                .containsEntry("breakdown", "country").containsEntry("period", "lifetime");
    }
}
