package com.pagelens.core.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.pagelens.core.auth.TokenService.TokenHealth;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.SyncRunRepository.SyncRun;

/**
 * Results of the analysis helpers (plan section 2), shared by the REST API and the MCP tools.
 * Every numeric value is nullable: NULL means "Instagram did not return it", never zero.
 */
public final class AnalysisModels {

    private AnalysisModels() {
    }

    /** Below this many posts a group's averages are flagged as unreliable. */
    public static final int SMALL_SAMPLE_POSTS = 5;

    public enum Metric {
        REACH, VIEWS, LIKES, COMMENTS, SHARES, SAVES, FOLLOWS, TOTAL_INTERACTIONS, ENGAGEMENT_RATE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Metric parse(String s) {
            if (s == null || s.isBlank()) {
                return REACH;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown metric '" + s + "'. Use one of: "
                        + java.util.Arrays.stream(values()).map(Metric::key).toList());
            }
        }
    }

    /** Post format as the analysis groups it: REELS, CAROUSEL, IMAGE, or VIDEO (pre-Reels feed video). */
    public enum Format { REELS, CAROUSEL, IMAGE, VIDEO }

    /** The time window an analysis covers, inclusive of {@code from}, exclusive of {@code to}. */
    public record Range(Instant from, Instant to, String label) {
    }

    /**
     * One post with its latest insight snapshot, flattened for analysis.
     *
     * @param interactions   total_interactions, or the sum of likes/comments/shares/saves when that is missing
     * @param engagementRate interactions / reach (0..1), null if either is unknown or reach is 0
     */
    public record PostStat(long postId, String igMediaId, Format format, Instant postedAt, String caption,
                           String permalink, Long reach, Long views, Long likes, Long comments, Long shares,
                           Long saves, Long follows, Long interactions, Double engagementRate) {

        public Double value(Metric m) {
            Long v = switch (m) {
                case REACH -> reach;
                case VIEWS -> views;
                case LIKES -> likes;
                case COMMENTS -> comments;
                case SHARES -> shares;
                case SAVES -> saves;
                case FOLLOWS -> follows;
                case TOTAL_INTERACTIONS -> interactions;
                case ENGAGEMENT_RATE -> null;
            };
            return m == Metric.ENGAGEMENT_RATE ? engagementRate : v == null ? null : v.doubleValue();
        }
    }

    /**
     * @param consideredPosts posts in range
     * @param excludedNoData  posts skipped because Instagram returned no value for the metric
     */
    public record RankedPosts(Metric metric, String order, Range range, int consideredPosts, int excludedNoData,
                              List<PostStat> posts) {
    }

    public record FormatStat(Format format, int posts, int postsWithInsights, Double avgReach, Double medianReach,
                             Double avgViews, Double avgInteractions, Double avgEngagementRate, Double avgSaves,
                             Double avgShares, boolean smallSample) {
    }

    public record FormatComparison(Range range, List<FormatStat> formats, String note) {
    }

    /**
     * One weekday × hour slot in the owner's time zone. {@code weekday} 1 = Monday … 7 = Sunday; 0 = all days.
     * {@code hour} 0-23; -1 = all hours.
     */
    public record TimeSlot(int weekday, int hour, int posts, Double avgValue, boolean smallSample) {
    }

    public record PostingTimes(Range range, Metric metric, String timeZone, int posts, List<TimeSlot> cells,
                               List<TimeSlot> byWeekday, List<TimeSlot> byHour, List<TimeSlot> bestSlots,
                               String note) {
    }

    public record GrowthDay(LocalDate day, Long followers, Long delta) {
    }

    /** Follows attributed to one post by Instagram, and the account's follower change on the day it went out. */
    public record PostGrowth(PostStat post, Long followsFromPost, Long followerDeltaThatDay) {
    }

    public record FollowerGrowth(Range range, Long startFollowers, Long endFollowers, Long netChange,
                                 List<GrowthDay> daily, List<PostGrowth> perPost, String note) {
    }

    public record CaptionBucket(String dimension, String bucket, int posts, Double avgReach,
                                Double avgEngagementRate, boolean smallSample) {
    }

    public record CaptionStats(Range range, int posts, List<CaptionBucket> buckets, String note) {
    }

    /**
     * Headline numbers for one account. Deltas compare completed UTC days.
     *
     * @param reachLast7d      sum of daily reach over the last 7 completed days (daily reach doesn't de-duplicate
     *                         across days, so this is an upper bound of unique accounts reached)
     */
    public record Overview(Account account, TokenHealth token, Long followersChange7d, Long followersChange30d,
                           Long reachLast7d, Long reachPrevious7d, Long viewsLast7d, Long viewsPrevious7d,
                           int postsLast30d, Double avgEngagementRateLast30d, List<PostStat> bestRecent,
                           List<PostStat> worstRecent, SyncRun lastRun) {
    }
}
