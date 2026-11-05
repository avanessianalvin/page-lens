package com.pagelens.core.analysis;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.pagelens.core.analysis.AnalysisModels.CaptionBucket;
import com.pagelens.core.analysis.AnalysisModels.Format;
import com.pagelens.core.analysis.AnalysisModels.FormatStat;
import com.pagelens.core.analysis.AnalysisModels.GrowthDay;
import com.pagelens.core.analysis.AnalysisModels.Metric;
import com.pagelens.core.analysis.AnalysisModels.PostGrowth;
import com.pagelens.core.analysis.AnalysisModels.PostStat;
import com.pagelens.core.analysis.AnalysisModels.TimeSlot;
import com.pagelens.core.repo.DailyMetricRepository.DailyMetric;
import com.pagelens.core.repo.InsightRepository.Snapshot;
import com.pagelens.core.repo.MediaRepository.Post;

/**
 * Pure aggregation over posts and daily metrics: no database, no clock, so every rule is unit-testable.
 * Missing values are skipped (never treated as zero), and averages report how many posts they are based on.
 */
public final class Analytics {

    private Analytics() {
    }

    // ------------------------------------------------------------------------------------------ flattening

    public static PostStat stat(Post p) {
        Snapshot s = p.latest();
        Long likes = s != null && s.likes() != null ? s.likes() : p.likeCount();
        Long comments = s != null && s.comments() != null ? s.comments() : p.commentsCount();
        Long reach = s == null ? null : s.reach();
        Long interactions = s == null ? null : s.totalInteractions();
        if (interactions == null) {
            interactions = sumKnown(likes, comments, s == null ? null : s.shares(), s == null ? null : s.saves());
        }
        Double er = interactions != null && reach != null && reach > 0 ? round((double) interactions / reach) : null;
        return new PostStat(p.id(), p.igMediaId(), format(p), p.postedAt(), p.caption(), p.permalink(), reach,
                s == null ? null : s.views(), likes, comments, s == null ? null : s.shares(),
                s == null ? null : s.saves(), s == null ? null : s.follows(), interactions, er);
    }

    public static Format format(Post p) {
        if ("REELS".equals(p.productType())) {
            return Format.REELS;
        }
        if ("CAROUSEL_ALBUM".equals(p.mediaType())) {
            return Format.CAROUSEL;
        }
        return "VIDEO".equals(p.mediaType()) ? Format.VIDEO : Format.IMAGE;
    }

    private static Long sumKnown(Long... parts) {
        long sum = 0;
        boolean any = false;
        for (Long v : parts) {
            if (v != null) {
                sum += v;
                any = true;
            }
        }
        return any ? sum : null;
    }

    // ------------------------------------------------------------------------------------------ ranking

    /** Top ({@code ascending=false}) or bottom posts by a metric; posts without a value are excluded. */
    public static List<PostStat> rank(List<PostStat> posts, Metric metric, int count, boolean ascending) {
        Comparator<PostStat> byValue = Comparator.comparingDouble(p -> p.value(metric));
        return posts.stream()
                .filter(p -> p.value(metric) != null)
                .sorted(ascending ? byValue : byValue.reversed())
                .limit(Math.max(1, count))
                .toList();
    }

    // ------------------------------------------------------------------------------------------ formats

    public static List<FormatStat> formats(List<PostStat> posts) {
        Map<Format, List<PostStat>> groups = new EnumMap<>(Format.class);
        for (PostStat p : posts) {
            groups.computeIfAbsent(p.format(), k -> new ArrayList<>()).add(p);
        }
        List<FormatStat> out = new ArrayList<>();
        groups.forEach((format, list) -> {
            List<PostStat> withReach = list.stream().filter(p -> p.reach() != null).toList();
            out.add(new FormatStat(format, list.size(), withReach.size(),
                    avg(list, PostStat::reach), median(list, PostStat::reach), avg(list, PostStat::views),
                    avg(list, PostStat::interactions), avgD(list, PostStat::engagementRate), avg(list, PostStat::saves),
                    avg(list, PostStat::shares), withReach.size() < AnalysisModels.SMALL_SAMPLE_POSTS));
        });
        out.sort(Comparator.comparing((FormatStat f) -> f.avgReach() == null ? -1 : f.avgReach()).reversed());
        return out;
    }

    // ------------------------------------------------------------------------------------------ timing

    public record Timing(List<TimeSlot> cells, List<TimeSlot> byWeekday, List<TimeSlot> byHour, List<TimeSlot> best) {
    }

    /**
     * Groups posts by weekday and hour <em>in the owner's zone</em>. A slot's average needs at least two posts to
     * qualify as a "best" slot; fewer is flagged as a small sample.
     */
    public static Timing timing(List<PostStat> posts, Metric metric, ZoneId zone) {
        Map<Integer, List<Double>> cells = new LinkedHashMap<>();
        Map<Integer, List<Double>> days = new LinkedHashMap<>();
        Map<Integer, List<Double>> hours = new LinkedHashMap<>();
        for (PostStat p : posts) {
            Double v = p.value(metric);
            if (v == null || p.postedAt() == null) {
                continue;
            }
            ZonedDateTime t = p.postedAt().atZone(zone);
            int d = t.getDayOfWeek().getValue();
            int h = t.getHour();
            cells.computeIfAbsent(d * 100 + h, k -> new ArrayList<>()).add(v);
            days.computeIfAbsent(d, k -> new ArrayList<>()).add(v);
            hours.computeIfAbsent(h, k -> new ArrayList<>()).add(v);
        }
        List<TimeSlot> cellList = new ArrayList<>();
        cells.forEach((k, vs) -> cellList.add(slot(k / 100, k % 100, vs)));
        cellList.sort(Comparator.comparingInt(TimeSlot::weekday).thenComparingInt(TimeSlot::hour));
        List<TimeSlot> dayList = new ArrayList<>();
        days.forEach((d, vs) -> dayList.add(slot(d, -1, vs)));
        dayList.sort(Comparator.comparingInt(TimeSlot::weekday));
        List<TimeSlot> hourList = new ArrayList<>();
        hours.forEach((h, vs) -> hourList.add(slot(0, h, vs)));
        hourList.sort(Comparator.comparingInt(TimeSlot::hour));
        List<TimeSlot> best = cellList.stream().filter(s -> s.posts() >= 2)
                .sorted(Comparator.comparingDouble(TimeSlot::avgValue).reversed()).limit(3).toList();
        return new Timing(cellList, dayList, hourList, best);
    }

    private static TimeSlot slot(int weekday, int hour, List<Double> values) {
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new TimeSlot(weekday, hour, values.size(), round(avg), values.size() < 2);
    }

    // ------------------------------------------------------------------------------------------ captions

    public static List<CaptionBucket> captions(List<PostStat> posts) {
        Map<String, List<PostStat>> groups = new LinkedHashMap<>();
        for (PostStat p : posts) {
            CaptionFeatures.Features f = CaptionFeatures.of(p.caption());
            groups.computeIfAbsent("length|" + f.lengthBucket(), k -> new ArrayList<>()).add(p);
            groups.computeIfAbsent("script|" + f.script(), k -> new ArrayList<>()).add(p);
            groups.computeIfAbsent("hashtags|" + f.hashtagBucket(), k -> new ArrayList<>()).add(p);
            groups.computeIfAbsent("cta|" + (f.hasCta() ? "with call to action" : "no call to action"),
                    k -> new ArrayList<>()).add(p);
        }
        List<String> order = List.of("none", "1-50", "51-150", "151-300", "300+", "0", "1-3", "4-10", "11+");
        List<CaptionBucket> out = new ArrayList<>();
        groups.forEach((key, list) -> {
            String[] parts = key.split("\\|", 2);
            long withReach = list.stream().filter(p -> p.reach() != null).count();
            out.add(new CaptionBucket(parts[0], parts[1], list.size(), avg(list, PostStat::reach),
                    avgD(list, PostStat::engagementRate), withReach < AnalysisModels.SMALL_SAMPLE_POSTS));
        });
        List<String> dims = List.of("length", "script", "hashtags", "cta");
        out.sort(Comparator.comparingInt((CaptionBucket b) -> dims.indexOf(b.dimension()))
                .thenComparingInt(b -> order.contains(b.bucket()) ? order.indexOf(b.bucket()) : 100)
                .thenComparing(CaptionBucket::bucket));
        return out;
    }

    // ------------------------------------------------------------------------------------------ growth

    public static List<GrowthDay> growthDays(List<DailyMetric> days) {
        return days.stream().filter(d -> d.followersCount() != null)
                .map(d -> new GrowthDay(d.day(), d.followersCount(), d.followerDelta())).toList();
    }

    /** Per post: follows Instagram attributes to it, plus the account's net follower change on its UTC day. */
    public static List<PostGrowth> perPost(List<PostStat> posts, List<DailyMetric> days) {
        Map<LocalDate, Long> deltaByDay = new LinkedHashMap<>();
        for (DailyMetric d : days) {
            if (d.followerDelta() != null) {
                deltaByDay.put(d.day(), d.followerDelta());
            }
        }
        return posts.stream()
                .filter(p -> p.postedAt() != null)
                .sorted(Comparator.comparing(PostStat::postedAt).reversed())
                .map(p -> new PostGrowth(p, p.follows(),
                        deltaByDay.get(LocalDate.ofInstant(p.postedAt(), ZoneOffset.UTC))))
                .toList();
    }

    // ------------------------------------------------------------------------------------------ helpers

    static Double avg(List<PostStat> list, Function<PostStat, Long> f) {
        return avgD(list, p -> {
            Long v = f.apply(p);
            return v == null ? null : v.doubleValue();
        });
    }

    static Double avgD(List<PostStat> list, Function<PostStat, Double> f) {
        double[] vs = list.stream().map(f).filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
        if (vs.length == 0) {
            return null;
        }
        double sum = 0;
        for (double v : vs) {
            sum += v;
        }
        return round(sum / vs.length);
    }

    static Double median(List<PostStat> list, Function<PostStat, Long> f) {
        double[] vs = list.stream().map(f).filter(Objects::nonNull).mapToDouble(Long::doubleValue).sorted().toArray();
        if (vs.length == 0) {
            return null;
        }
        int mid = vs.length / 2;
        return round(vs.length % 2 == 1 ? vs[mid] : (vs[mid - 1] + vs[mid]) / 2);
    }

    /** 4 significant decimals: enough for engagement rates, readable for counts. */
    static Double round(double v) {
        return Math.round(v * 10_000d) / 10_000d;
    }
}
