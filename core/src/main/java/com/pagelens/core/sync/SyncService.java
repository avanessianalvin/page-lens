package com.pagelens.core.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.pagelens.core.auth.TokenService;
import com.pagelens.core.config.MetricCatalog;
import com.pagelens.core.config.SyncProperties;
import com.pagelens.core.instagram.GraphApiException;
import com.pagelens.core.instagram.GraphJson;
import com.pagelens.core.instagram.GraphApiException.Kind;
import com.pagelens.core.instagram.IgModels.AccountRef;
import com.pagelens.core.instagram.IgModels.IgComment;
import com.pagelens.core.instagram.IgModels.IgMedia;
import com.pagelens.core.instagram.IgModels.IgProfile;
import com.pagelens.core.instagram.IgModels.Insights;
import com.pagelens.core.instagram.InstagramApi;
import com.pagelens.core.logging.CorrelationId;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AudienceRepository;
import com.pagelens.core.repo.CommentRepository;
import com.pagelens.core.repo.DailyMetricRepository;
import com.pagelens.core.repo.InsightRepository;
import com.pagelens.core.repo.InsightRepository.Snapshot;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.repo.MediaRepository.SyncCandidate;
import com.pagelens.core.repo.SyncRunRepository;
import com.pagelens.core.repo.SyncRunRepository.Status;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sync engine (plan section 7). Every step is idempotent (upserts on Instagram ids), so reruns never
 * duplicate data. Steps fail independently: one bad post doesn't stop the run. An invalid token or a rate limit
 * aborts the run, because continuing would only burn budget.
 */
@Service
public class SyncService {

    public enum JobType { FULL, BACKFILL, STORIES }

    public enum Trigger { UI, MCP, SCHEDULER }

    /** Thrown when a run for the account is already in progress (in this or another PageLens process). */
    public static class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final Duration STALE_RUN = Duration.ofHours(2);
    private static final int BACKFILL_INSIGHTS_LIMIT = 500;
    private static final int COMMENT_THREADS_PER_POST = 1000;

    /** Metric name → snapshot column. Everything else lands in extra_metrics. */
    private static final Set<String> MAPPED_MEDIA_METRICS = Set.of("reach", "views", "likes", "comments", "shares",
            "saved", "follows", "total_interactions", "profile_visits", "ig_reels_avg_watch_time");

    private final AccountRepository accounts;
    private final MediaRepository media;
    private final InsightRepository insights;
    private final CommentRepository comments;
    private final DailyMetricRepository daily;
    private final AudienceRepository audience;
    private final SyncRunRepository runs;
    private final TokenService tokens;
    private final InstagramApi api;
    private final MetricCatalog catalog;
    private final SyncProperties props;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final TaskExecutor executor;
    private final Clock clock;

    /** Accounts whose /stories edge was refused (e.g. Instagram Login); not retried until restart. */
    private final Set<Long> storiesUnsupported = ConcurrentHashMap.newKeySet();

    public SyncService(AccountRepository accounts, MediaRepository media, InsightRepository insights,
                       CommentRepository comments, DailyMetricRepository daily, AudienceRepository audience,
                       SyncRunRepository runs, TokenService tokens, InstagramApi api, MetricCatalog catalog,
                       SyncProperties props, GraphJson json, TransactionTemplate tx,
                       @Qualifier("applicationTaskExecutor") TaskExecutor executor, Clock clock) {
        this.accounts = accounts;
        this.media = media;
        this.insights = insights;
        this.comments = comments;
        this.daily = daily;
        this.audience = audience;
        this.runs = runs;
        this.tokens = tokens;
        this.api = api;
        this.catalog = catalog;
        this.props = props;
        this.json = json.mapper();
        this.tx = tx;
        this.executor = executor;
        this.clock = clock;
    }

    /** Starts a run in the background (UI "Sync now", MCP sync_now). Returns the sync_run id immediately. */
    public long startAsync(long accountId, JobType job, Trigger trigger) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account " + accountId));
        String cid = "sync-" + java.util.UUID.randomUUID();
        long runId = claim(account, job, trigger, cid);
        executor.execute(() -> {
            try (var ignored = CorrelationId.use(cid)) {
                execute(runId, account, job);
            }
        });
        return runId;
    }

    /** Runs synchronously (scheduler, tests). Returns the finished run's status. */
    public Status run(long accountId, JobType job, Trigger trigger) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account " + accountId));
        try (var ignored = CorrelationId.open("sync")) {
            long runId = claim(account, job, trigger, CorrelationId.current());
            return execute(runId, account, job);
        }
    }

    /** Cleans up runs left RUNNING by a process that died. */
    public int failStaleRuns() {
        return runs.failStale(clock.instant().minus(STALE_RUN));
    }

    private long claim(Account account, JobType job, Trigger trigger, String cid) {
        return tx.execute(status -> {
            accounts.lockForUpdate(account.id()); // serialises claims across processes
            if (runs.isRunning(account.id(), clock.instant().minus(STALE_RUN))) {
                throw new AlreadyRunningException("A sync for @" + account.username() + " is already running");
            }
            return runs.start(account.id(), job.name(), trigger.name().toLowerCase(), cid);
        });
    }

    // ------------------------------------------------------------------------------------------ execution

    private final class Run {
        final long runId;
        final Account account;
        final AccountRef ref;
        int items;
        final List<String> errors = new ArrayList<>();
        boolean aborted;

        Run(long runId, Account account, AccountRef ref) {
            this.runId = runId;
            this.account = account;
            this.ref = ref;
        }

        /** Runs one step; classifies failures. Returns false if the run must stop. */
        boolean step(String name, Runnable body) {
            if (aborted) {
                return false;
            }
            try {
                body.run();
                return true;
            } catch (GraphApiException e) {
                if (e.kind() == Kind.TOKEN_INVALID) {
                    tokens.markInvalid(account.id(), e.getMessage());
                    abort(name, "token invalid: " + e.getMessage());
                } else if (e.kind() == Kind.RATE_LIMITED) {
                    abort(name, "rate limited: " + e.getMessage());
                } else {
                    errors.add(name + ": " + e.getMessage());
                    log.warn("Sync step {} failed for @{}: {}", name, account.username(), e.getMessage());
                }
            } catch (RuntimeException e) {
                errors.add(name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("Sync step {} failed for @{}", name, account.username(), e);
            }
            return !aborted;
        }

        void abort(String step, String reason) {
            aborted = true;
            errors.add(step + ": " + reason);
            log.warn("Sync for @{} aborted in {}: {}", account.username(), step, reason);
        }
    }

    private Status execute(long runId, Account account, JobType job) {
        Instant started = clock.instant();
        log.info("Sync {} started for @{} (run {})", job, account.username(), runId);
        AccountRef ref;
        try {
            ref = tokens.ref(account);
        } catch (RuntimeException e) {
            runs.finish(runId, Status.FAILED, 0, e.getMessage());
            log.warn("Sync {} for @{} not started: {}", job, account.username(), e.getMessage());
            return Status.FAILED;
        }
        Run run = new Run(runId, account, ref);
        try {
            switch (job) {
                case FULL -> full(run, false);
                case BACKFILL -> full(run, true);
                case STORIES -> stories(run);
            }
        } catch (RuntimeException e) {
            run.errors.add("unexpected: " + e);
            run.aborted = true;
            log.error("Sync {} for @{} crashed", job, account.username(), e);
        }
        Status status = run.aborted ? Status.FAILED : run.errors.isEmpty() ? Status.SUCCEEDED : Status.PARTIAL;
        if (job == JobType.STORIES && storiesUnsupported.contains(account.id()) && run.errors.isEmpty()) {
            status = Status.SKIPPED;
        }
        String error = run.errors.isEmpty() ? null : truncate(String.join("\n", run.errors), 4000);
        runs.finish(runId, status, run.items, error);
        if (job != JobType.STORIES && status != Status.FAILED) {
            accounts.touchSynced(account.id(), clock.instant());
        }
        log.info("Sync {} for @{} finished: {} ({} items, {} ms)", job, account.username(), status, run.items,
                Duration.between(started, clock.instant()).toMillis());
        return status;
    }

    private void full(Run run, boolean backfill) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        if (!run.step("profile", () -> syncProfile(run, today))) {
            return;
        }
        boolean firstSync = media.countForAccount(run.account.id()) == 0;
        if (!run.step("media", () -> syncMedia(run, backfill || firstSync, now))) {
            return;
        }

        List<SyncCandidate> targets = new ArrayList<>(media.recentPosts(run.account.id(), now.minus(days(props.recentDays()))));
        int olderLimit = backfill || firstSync ? BACKFILL_INSIGHTS_LIMIT : props.olderRefreshBatch();
        targets.addAll(media.staleOlderPosts(run.account.id(), now.minus(days(props.recentDays())),
                now.minus(days(props.olderRefreshDays())), olderLimit));
        for (SyncCandidate c : targets) {
            if (!run.step("insights " + c.igMediaId(), () -> syncMediaInsights(run, c, now))) {
                return;
            }
        }

        for (SyncCandidate c : media.recentPosts(run.account.id(), now.minus(days(props.commentDays())))) {
            if (!run.step("comments " + c.igMediaId(), () -> syncComments(run, c, now))) {
                return;
            }
        }

        int dayCount = backfill || !daily.hasAny(run.account.id()) ? props.backfillDays() : props.dailyLookbackDays();
        for (int i = dayCount; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            if (!run.step("daily " + day, () -> syncDay(run, day))) {
                return;
            }
        }

        run.step("audience", () -> syncAudience(run, now, today));
    }

    private void syncProfile(Run run, LocalDate today) {
        IgProfile p = api.profile(run.ref);
        accounts.updateProfile(run.account.id(), p);
        if (p.followersCount() != null) {
            daily.recordFollowers(run.account.id(), today, p.followersCount());
        }
        run.items++;
    }

    private void syncMedia(Run run, boolean all, Instant now) {
        Instant recentCutoff = now.minus(days(props.recentDays()));
        int cap = all ? 0 : props.mediaPageLimit();
        // Regular runs stop at the first already-known post older than the recent window: everything past it is
        // known, and its insights are refreshed separately.
        List<IgMedia> fetched = api.media(run.ref, cap, m -> all
                || m.timestamp() == null || m.timestamp().isAfter(recentCutoff) || !media.exists(m.id()));
        for (IgMedia m : fetched) {
            media.upsert(run.account.id(), m, false, json.writeValueAsString(m.raw()));
            run.items++;
        }
    }

    private void syncMediaInsights(Run run, SyncCandidate c, Instant now) {
        List<String> metrics = catalog.mediaMetricsFor(c.productType(), run.account.loginType());
        try {
            Insights result = api.mediaInsights(run.ref, c.igMediaId(), metrics);
            if (!result.values().isEmpty()) {
                insights.insert(c.id(), snapshot(result, now));
                run.items++;
            }
        } catch (GraphApiException e) {
            if (e.kind() != Kind.NOT_ENOUGH_DATA) {
                throw e;
            }
            log.debug("Not enough data for insights on {}", c.igMediaId());
        }
        media.markInsightsFetched(c.id(), now);
    }

    private void syncComments(Run run, SyncCandidate c, Instant now) {
        List<IgComment> threads = api.comments(run.ref, c.igMediaId());
        int n = 0;
        for (IgComment thread : threads) {
            if (n >= COMMENT_THREADS_PER_POST) {
                break;
            }
            run.items += comments.upsertThread(c.id(), thread);
            n++;
        }
        media.markCommentsFetched(c.id(), now);
    }

    private void syncDay(Run run, LocalDate day) {
        Insights result = api.accountDay(run.ref, day, catalog.accountDailyMetrics());
        ObjectNode extra = json.createObjectNode();
        result.values().forEach((k, v) -> {
            if (!catalog.accountDailyMetrics().contains(k)) {
                extra.put(k, v);
            }
        });
        try {
            Map<String, Long> follows = api.accountDayBreakdown(run.ref, day, "follows_and_unfollows", "follow_type");
            if (!follows.isEmpty()) {
                ObjectNode f = extra.putObject("follows_and_unfollows");
                follows.forEach(f::put);
            }
        } catch (GraphApiException e) {
            if (e.kind() == Kind.TOKEN_INVALID || e.kind() == Kind.RATE_LIMITED) {
                throw e;
            }
            log.debug("follows_and_unfollows unavailable for {}: {}", day, e.getMessage());
        }
        if (!result.values().isEmpty() || !extra.isEmpty()) {
            daily.upsertMetrics(run.account.id(), day, result.values(), extra.isEmpty() ? null : extra.toString());
            run.items++;
        }
    }

    private void syncAudience(Run run, Instant now, LocalDate today) {
        MetricCatalog.Demographics d = catalog.demographics();
        Instant latest = audience.latestCapture(run.account.id(), d.metric()).orElse(Instant.EPOCH);
        if (!latest.isBefore(today.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            return; // already captured today
        }
        List<String> failed = new ArrayList<>();
        for (String breakdown : d.breakdowns()) {
            try {
                Map<String, Long> values = api.demographics(run.ref, d.metric(), d.timeframe(), breakdown);
                audience.insert(run.account.id(), now, d.metric(), breakdown, values);
                run.items += values.size();
            } catch (GraphApiException e) {
                if (e.kind() == Kind.TOKEN_INVALID || e.kind() == Kind.RATE_LIMITED) {
                    throw e;
                }
                failed.add(breakdown + " (" + e.getMessage() + ")");
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("demographics unavailable for " + failed
                    + "; Meta returns none for accounts under 100 followers");
        }
    }

    private void stories(Run run) {
        if (storiesUnsupported.contains(run.account.id())) {
            return;
        }
        Instant now = clock.instant();
        List<IgMedia> live;
        try {
            live = api.stories(run.ref);
        } catch (GraphApiException e) {
            if (e.kind() == Kind.INVALID_REQUEST || e.kind() == Kind.PERMISSION) {
                storiesUnsupported.add(run.account.id());
                log.warn("Stories are not available for @{} via {} ({}); story polling disabled until restart",
                        run.account.username(), run.account.loginType(), e.getMessage());
                return;
            }
            throw e;
        }
        for (IgMedia story : live) {
            run.step("story " + story.id(), () -> {
                long mediaId = media.upsert(run.account.id(), story, true, json.writeValueAsString(story.raw()));
                run.items++;
                syncMediaInsights(run, new SyncCandidate(mediaId, story.id(), "STORY", true, story.timestamp(), null), now);
            });
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Snapshot snapshot(Insights r, Instant at) {
        Map<String, Long> v = r.values();
        Map<String, Long> extra = new LinkedHashMap<>();
        v.forEach((k, val) -> {
            if (!MAPPED_MEDIA_METRICS.contains(k)) {
                extra.put(k, val);
            }
        });
        Set<String> unsupported = new LinkedHashSet<>(r.unsupported());
        ObjectNode extraJson = json.createObjectNode();
        extra.forEach(extraJson::put);
        if (!unsupported.isEmpty()) {
            var arr = extraJson.putArray("_unsupported");
            unsupported.forEach(arr::add);
        }
        return new Snapshot(at, v.get("reach"), v.get("views"), v.get("likes"), v.get("comments"), v.get("shares"),
                v.get("saved"), v.get("follows"), v.get("total_interactions"), v.get("profile_visits"),
                v.get("ig_reels_avg_watch_time"), extraJson.isEmpty() ? null : extraJson.toString());
    }

    private static Duration days(int n) {
        return Duration.ofDays(n);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
