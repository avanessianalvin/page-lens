package com.pagelens.core.analysis;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.pagelens.core.analysis.AnalysisModels.CaptionStats;
import com.pagelens.core.analysis.AnalysisModels.FollowerGrowth;
import com.pagelens.core.analysis.AnalysisModels.FormatComparison;
import com.pagelens.core.analysis.AnalysisModels.GrowthDay;
import com.pagelens.core.analysis.AnalysisModels.Metric;
import com.pagelens.core.analysis.AnalysisModels.Overview;
import com.pagelens.core.analysis.AnalysisModels.PostStat;
import com.pagelens.core.analysis.AnalysisModels.PostingTimes;
import com.pagelens.core.analysis.AnalysisModels.Range;
import com.pagelens.core.analysis.AnalysisModels.RankedPosts;
import com.pagelens.core.auth.TokenService;
import com.pagelens.core.config.PageLensProperties;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.DailyMetricRepository;
import com.pagelens.core.repo.DailyMetricRepository.DailyMetric;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.repo.SyncRunRepository;

/**
 * Analysis helpers (plan section 2) over locally stored data. Never calls Instagram, so the dashboard and the MCP
 * tools can ask freely without spending rate budget.
 */
@Service
public class AnalysisService {

    public static final String DEFAULT_PERIOD = "last_90_days";

    private final AccountRepository accounts;
    private final MediaRepository media;
    private final DailyMetricRepository daily;
    private final SyncRunRepository runs;
    private final TokenService tokens;
    private final PageLensProperties props;
    private final Clock clock;

    public AnalysisService(AccountRepository accounts, MediaRepository media, DailyMetricRepository daily,
                           SyncRunRepository runs, TokenService tokens, PageLensProperties props, Clock clock) {
        this.accounts = accounts;
        this.media = media;
        this.daily = daily;
        this.runs = runs;
        this.tokens = tokens;
        this.props = props;
        this.clock = clock;
    }

    public ZoneId zone() {
        return props.zoneId();
    }

    public Range range(String period, String from, String to) {
        return range(period, from, to, DEFAULT_PERIOD);
    }

    public Range range(String period, String from, String to, String defaultPeriod) {
        return Periods.resolve(period, from, to, defaultPeriod, zone(), clock);
    }

    public List<PostStat> posts(long accountId, Range range) {
        requireAccount(accountId);
        return media.postsInRange(accountId, range.from(), range.to()).stream().map(Analytics::stat).toList();
    }

    public RankedPosts ranked(long accountId, Metric metric, int count, boolean ascending, Range range) {
        List<PostStat> all = posts(accountId, range);
        List<PostStat> ranked = Analytics.rank(all, metric, Math.min(Math.max(count, 1), 50), ascending);
        long withValue = all.stream().filter(p -> p.value(metric) != null).count();
        return new RankedPosts(metric, ascending ? "bottom" : "top", range, all.size(),
                (int) (all.size() - withValue), ranked);
    }

    public FormatComparison formats(long accountId, Range range) {
        return new FormatComparison(range, Analytics.formats(posts(accountId, range)),
                "Averages skip posts where Instagram returned no value. Engagement rate = interactions / reach. "
                        + "Formats with fewer than " + AnalysisModels.SMALL_SAMPLE_POSTS
                        + " posts with insights are flagged smallSample.");
    }

    public PostingTimes postingTimes(long accountId, Metric metric, Range range) {
        List<PostStat> posts = posts(accountId, range);
        Analytics.Timing t = Analytics.timing(posts, metric, zone());
        int counted = t.cells().stream().mapToInt(AnalysisModels.TimeSlot::posts).sum();
        return new PostingTimes(range, metric, zone().getId(), counted, t.cells(), t.byWeekday(), t.byHour(),
                t.best(), "Weekday 1 = Monday. Times are in " + zone().getId()
                + ". Slots with a single post are flagged smallSample and never chosen as a best slot. "
                + "This shows when past posts did well, which is correlation, not a guarantee.");
    }

    public FollowerGrowth growth(long accountId, Range range) {
        requireAccount(accountId);
        LocalDate from = LocalDate.ofInstant(range.from().isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                ? Instant.parse("2000-01-01T00:00:00Z") : range.from(), ZoneOffset.UTC);
        LocalDate to = LocalDate.ofInstant(range.to(), ZoneOffset.UTC);
        List<DailyMetric> days = daily.range(accountId, from, to);
        List<GrowthDay> growth = Analytics.growthDays(days);
        Long start = growth.isEmpty() ? null : growth.getFirst().followers();
        Long end = growth.isEmpty() ? null : growth.getLast().followers();
        return new FollowerGrowth(range, start, end, start == null || end == null ? null : end - start, growth,
                Analytics.perPost(posts(accountId, range), days),
                "Daily follower counts are snapshots taken at each sync (UTC days); the history starts when "
                        + "PageLens first synced the account. followsFromPost is Instagram's own attribution.");
    }

    public CaptionStats captions(long accountId, Range range) {
        List<PostStat> posts = posts(accountId, range);
        return new CaptionStats(range, posts.size(), Analytics.captions(posts),
                "Each post counts once per dimension (length in characters, script, hashtags, call to action). "
                        + "Script is detected from the letters used; 'farsi' means Arabic script.");
    }

    public Overview overview(long accountId) {
        Account account = requireAccount(accountId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<DailyMetric> days = daily.range(accountId, today.minusDays(40), today);

        Range last30 = new Range(now.minusSeconds(30L * 86400), now, "last_30_days");
        List<PostStat> recent = posts(accountId, last30);
        return new Overview(account, tokens.health(accountId),
                followerChange(days, today, 7), followerChange(days, today, 30),
                sum(days, today.minusDays(7), today.minusDays(1), DailyMetric::reach),
                sum(days, today.minusDays(14), today.minusDays(8), DailyMetric::reach),
                sum(days, today.minusDays(7), today.minusDays(1), DailyMetric::views),
                sum(days, today.minusDays(14), today.minusDays(8), DailyMetric::views),
                recent.size(), Analytics.avgD(recent, PostStat::engagementRate),
                Analytics.rank(recent, Metric.REACH, 3, false), Analytics.rank(recent, Metric.REACH, 3, true),
                runs.recent(accountId, 1).stream().findFirst().orElse(null));
    }

    /** Resolves an account by id or username (with or without @); with one account connected, blank picks it. */
    public Account resolveAccount(String idOrUsername) {
        List<Account> all = accounts.findAll();
        if (idOrUsername == null || idOrUsername.isBlank()) {
            if (all.size() == 1) {
                return all.getFirst();
            }
            throw new IllegalArgumentException(all.isEmpty() ? "No Instagram account is connected yet"
                    : "Several accounts are connected; pass account as one of "
                    + all.stream().map(a -> "@" + a.username() + " (id " + a.id() + ")").toList());
        }
        String key = idOrUsername.trim();
        String name = key.startsWith("@") ? key.substring(1) : key;
        Optional<Account> match = all.stream()
                .filter(a -> String.valueOf(a.id()).equals(key) || a.username().equalsIgnoreCase(name))
                .findFirst();
        return match.orElseThrow(() -> new IllegalArgumentException("No connected account matches '" + key
                + "'. Connected: " + all.stream().map(a -> "@" + a.username()).toList()));
    }

    private Account requireAccount(long id) {
        return accounts.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown account " + id));
    }

    private static Long followerChange(List<DailyMetric> days, LocalDate today, int back) {
        Long latest = null;
        Long earlier = null;
        for (DailyMetric d : days) { // ascending by day
            if (d.followersCount() == null) {
                continue;
            }
            if (!d.day().isAfter(today.minusDays(back))) {
                earlier = d.followersCount();
            }
            latest = d.followersCount();
        }
        return latest == null || earlier == null ? null : latest - earlier;
    }

    private static Long sum(List<DailyMetric> days, LocalDate from, LocalDate to,
                            java.util.function.Function<DailyMetric, Long> f) {
        long total = 0;
        boolean any = false;
        for (DailyMetric d : days) {
            if (!d.day().isBefore(from) && !d.day().isAfter(to) && f.apply(d) != null) {
                total += f.apply(d);
                any = true;
            }
        }
        return any ? total : null;
    }
}
