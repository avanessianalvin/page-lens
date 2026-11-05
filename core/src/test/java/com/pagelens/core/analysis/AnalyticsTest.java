package com.pagelens.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pagelens.core.analysis.AnalysisModels.Format;
import com.pagelens.core.analysis.AnalysisModels.Metric;
import com.pagelens.core.analysis.AnalysisModels.PostStat;
import com.pagelens.core.repo.DailyMetricRepository.DailyMetric;
import com.pagelens.core.repo.InsightRepository.Snapshot;
import com.pagelens.core.repo.MediaRepository.Post;

class AnalyticsTest {

    static final ZoneId YEREVAN = ZoneId.of("Asia/Yerevan"); // UTC+4

    static Post post(long id, String mediaType, String product, String posted, String caption, Long reach, Long ti,
                     Long likes) {
        Snapshot s = reach == null && ti == null && likes == null ? null
                : new Snapshot(Instant.parse(posted), reach, null, likes, null, null, null, 3L, ti, null, null, null);
        return new Post(id, 1, "m" + id, mediaType, product, caption, null, null, null, Instant.parse(posted), false,
                null, null, null, s);
    }

    @Test
    void engagementRateUsesTotalInteractionsOrFallsBackToKnownParts() {
        PostStat withTotal = Analytics.stat(post(1, "VIDEO", "REELS", "2026-11-01T10:00:00Z", "", 1000L, 120L, 80L));
        assertThat(withTotal.engagementRate()).isEqualTo(0.12);

        PostStat fallback = Analytics.stat(post(2, "IMAGE", "FEED", "2026-11-01T10:00:00Z", "", 500L, null, 50L));
        assertThat(fallback.interactions()).isEqualTo(50L);
        assertThat(fallback.engagementRate()).isEqualTo(0.1);

        PostStat none = Analytics.stat(post(3, "IMAGE", "FEED", "2026-11-01T10:00:00Z", "", null, null, null));
        assertThat(none.engagementRate()).isNull(); // never 0 for "unknown"
        assertThat(none.reach()).isNull();
    }

    @Test
    void formatClassification() {
        assertThat(Analytics.format(post(1, "VIDEO", "REELS", "2026-11-01T10:00:00Z", "", 1L, 1L, 1L))).isEqualTo(Format.REELS);
        assertThat(Analytics.format(post(1, "CAROUSEL_ALBUM", "FEED", "2026-11-01T10:00:00Z", "", 1L, 1L, 1L))).isEqualTo(Format.CAROUSEL);
        assertThat(Analytics.format(post(1, "IMAGE", "FEED", "2026-11-01T10:00:00Z", "", 1L, 1L, 1L))).isEqualTo(Format.IMAGE);
        assertThat(Analytics.format(post(1, "VIDEO", "FEED", "2026-11-01T10:00:00Z", "", 1L, 1L, 1L))).isEqualTo(Format.VIDEO);
    }

    @Test
    void rankingExcludesMissingValues() {
        List<PostStat> posts = List.of(
                Analytics.stat(post(1, "IMAGE", "FEED", "2026-11-01T10:00:00Z", "", 100L, 10L, 5L)),
                Analytics.stat(post(2, "IMAGE", "FEED", "2026-11-02T10:00:00Z", "", 300L, 9L, 5L)),
                Analytics.stat(post(3, "IMAGE", "FEED", "2026-11-03T10:00:00Z", "", null, null, null)));

        assertThat(Analytics.rank(posts, Metric.REACH, 5, false)).extracting(PostStat::postId).containsExactly(2L, 1L);
        assertThat(Analytics.rank(posts, Metric.REACH, 1, true)).extracting(PostStat::postId).containsExactly(1L);
        assertThat(Analytics.rank(posts, Metric.ENGAGEMENT_RATE, 5, false)).extracting(PostStat::postId).containsExactly(1L, 2L);
    }

    @Test
    void formatComparisonAveragesOnlyKnownValuesAndFlagsSmallSamples() {
        List<PostStat> posts = List.of(
                Analytics.stat(post(1, "VIDEO", "REELS", "2026-11-01T10:00:00Z", "", 1000L, 100L, 1L)),
                Analytics.stat(post(2, "VIDEO", "REELS", "2026-11-02T10:00:00Z", "", 3000L, 150L, 1L)),
                Analytics.stat(post(3, "VIDEO", "REELS", "2026-11-03T10:00:00Z", "", null, null, null)),
                Analytics.stat(post(4, "IMAGE", "FEED", "2026-11-03T10:00:00Z", "", 500L, 50L, 1L)));

        var formats = Analytics.formats(posts);
        assertThat(formats.getFirst().format()).isEqualTo(Format.REELS); // sorted by avg reach
        assertThat(formats.getFirst().posts()).isEqualTo(3);
        assertThat(formats.getFirst().postsWithInsights()).isEqualTo(2);
        assertThat(formats.getFirst().avgReach()).isEqualTo(2000.0);
        assertThat(formats.getFirst().medianReach()).isEqualTo(2000.0);
        assertThat(formats.getFirst().smallSample()).isTrue();
        assertThat(formats.get(1).avgEngagementRate()).isEqualTo(0.1);
    }

    @Test
    void timingBucketsInOwnerZoneAndNeedTwoPostsForBestSlot() {
        // 2026-11-02 is a Monday. 15:30 UTC = 19:30 in Yerevan.
        List<PostStat> posts = List.of(
                Analytics.stat(post(1, "IMAGE", "FEED", "2026-11-02T15:30:00Z", "", 100L, 10L, 1L)),
                Analytics.stat(post(2, "IMAGE", "FEED", "2026-11-09T15:10:00Z", "", 300L, 30L, 1L)),
                Analytics.stat(post(3, "IMAGE", "FEED", "2026-11-03T05:00:00Z", "", 9000L, 900L, 1L)));

        var t = Analytics.timing(posts, Metric.REACH, YEREVAN);

        assertThat(t.cells()).anySatisfy(c -> {
            assertThat(c.weekday()).isEqualTo(1);
            assertThat(c.hour()).isEqualTo(19);
            assertThat(c.posts()).isEqualTo(2);
            assertThat(c.avgValue()).isEqualTo(200.0);
            assertThat(c.smallSample()).isFalse();
        });
        // The Tuesday 09:00 post is a single post: higher, but flagged and never "best".
        assertThat(t.best()).singleElement().satisfies(b -> assertThat(b.hour()).isEqualTo(19));
        assertThat(t.byWeekday()).extracting(AnalysisModels.TimeSlot::weekday).containsExactly(1, 2);
    }

    @Test
    void captionFeatures() {
        var fa = CaptionFeatures.of("سلام دوستان! لینک در بیو #ریلز #سفر");
        assertThat(fa.script()).isEqualTo("farsi");
        assertThat(fa.hashtags()).isEqualTo(2);
        assertThat(fa.hasCta()).isTrue();

        var hy = CaptionFeatures.of("Բարեւ ձեզ, այսօր նոր տեսանյութ");
        assertThat(hy.script()).isEqualTo("armenian");
        assertThat(hy.hasCta()).isFalse();

        assertThat(CaptionFeatures.of("Hello friends, tag a friend!").script()).isEqualTo("latin");
        assertThat(CaptionFeatures.of("Hello سلام Բարեւ").script()).isEqualTo("mixed");
        assertThat(CaptionFeatures.of(null).lengthBucket()).isEqualTo("none");
        assertThat(CaptionFeatures.of("email me at a#b.com").hashtags()).isZero(); // not a hashtag mid-word
        assertThat(CaptionFeatures.of("x".repeat(400)).lengthBucket()).isEqualTo("300+");
    }

    @Test
    void captionBucketsAreOrderedByDimension() {
        List<PostStat> posts = List.of(
                Analytics.stat(post(1, "IMAGE", "FEED", "2026-11-01T10:00:00Z", "short #a", 100L, 10L, 1L)),
                Analytics.stat(post(2, "IMAGE", "FEED", "2026-11-01T10:00:00Z", "", 300L, 30L, 1L)));
        var buckets = Analytics.captions(posts);
        assertThat(buckets).extracting(AnalysisModels.CaptionBucket::dimension)
                .containsSubsequence("length", "script", "hashtags", "cta");
        assertThat(buckets).filteredOn(b -> b.dimension().equals("length")).extracting(AnalysisModels.CaptionBucket::bucket)
                .containsExactly("none", "1-50");
    }

    @Test
    void perPostGrowthJoinsFollowerDeltaOnUtcPostingDay() {
        var p = Analytics.stat(post(1, "IMAGE", "FEED", "2026-11-02T23:30:00Z", "", 100L, 10L, 1L));
        var days = List.of(new DailyMetric(LocalDate.of(2026, 11, 2), null, null, null, null, null, null, null, null,
                null, null, 1000L, 12L, null));
        var growth = Analytics.perPost(List.of(p), days);
        assertThat(growth).singleElement().satisfies(g -> {
            assertThat(g.followerDeltaThatDay()).isEqualTo(12L);
            assertThat(g.followsFromPost()).isEqualTo(3L);
        });
    }

    @Test
    void periodsResolveInOwnerZone() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
        var r = Periods.resolve(null, "2026-11-01", "2026-11-02", "last_90_days", YEREVAN, clock);
        assertThat(r.from()).isEqualTo(Instant.parse("2026-10-31T20:00:00Z"));
        assertThat(r.to()).isEqualTo(Instant.parse("2026-11-02T20:00:00Z"));

        var last7 = Periods.resolve("last_7_days", null, null, "last_90_days", YEREVAN, clock);
        assertThat(last7.from()).isEqualTo(Instant.parse("2026-10-29T12:00:00Z"));
        assertThatThrownBy(() -> Periods.resolve("last_week", null, null, "x", YEREVAN, clock))
                .hasMessageContaining("last_7_days");
        assertThatThrownBy(() -> Metric.parse("impressions")).hasMessageContaining("engagement_rate");
    }
}
