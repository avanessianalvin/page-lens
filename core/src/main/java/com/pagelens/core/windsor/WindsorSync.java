package com.pagelens.core.windsor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pagelens.core.config.MetricCatalog;
import com.pagelens.core.config.SyncProperties;
import com.pagelens.core.config.WindsorProperties;
import com.pagelens.core.instagram.GraphJson;
import com.pagelens.core.instagram.IgModels.IgComment;
import com.pagelens.core.instagram.IgModels.IgProfile;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AudienceRepository;
import com.pagelens.core.repo.CommentRepository;
import com.pagelens.core.repo.DailyMetricRepository;
import com.pagelens.core.repo.InsightRepository;
import com.pagelens.core.repo.InsightRepository.Snapshot;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.windsor.WindsorMapper.MediaRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sync steps for {@code WINDSOR} accounts, called by {@code SyncService} (which owns claiming, sync_run rows and
 * status). Writes through the same repositories and upsert rules as the Graph sync, so the analysis, dashboard
 * and MCP tools treat Windsor data like any other. Steps fail independently; a rejected API key or Windsor's
 * request limit stops the run.
 */
@Component
public class WindsorSync {

    /** What a run did; SyncService turns it into SUCCEEDED / PARTIAL / FAILED. */
    public record Outcome(int items, List<String> errors, boolean aborted) {
    }

    private static final Logger log = LoggerFactory.getLogger(WindsorSync.class);

    private final WindsorClient client;
    private final WindsorProperties props;
    private final SyncProperties sync;
    private final MetricCatalog catalog;
    private final AccountRepository accounts;
    private final MediaRepository media;
    private final InsightRepository insights;
    private final CommentRepository comments;
    private final DailyMetricRepository daily;
    private final AudienceRepository audience;
    private final JsonMapper json;
    private final Clock clock;

    public WindsorSync(WindsorClient client, WindsorProperties props, SyncProperties sync, MetricCatalog catalog,
                       AccountRepository accounts, MediaRepository media, InsightRepository insights,
                       CommentRepository comments, DailyMetricRepository daily, AudienceRepository audience,
                       GraphJson json, Clock clock) {
        this.client = client;
        this.props = props;
        this.sync = sync;
        this.catalog = catalog;
        this.accounts = accounts;
        this.media = media;
        this.insights = insights;
        this.comments = comments;
        this.daily = daily;
        this.audience = audience;
        this.json = json.mapper();
        this.clock = clock;
    }

    private final class Run {
        final Account account;
        int items;
        final List<String> errors = new ArrayList<>();
        boolean aborted;

        Run(Account account) {
            this.account = account;
        }

        void step(String name, Runnable body) {
            if (aborted) {
                return;
            }
            try {
                body.run();
            } catch (WindsorException e) {
                errors.add(name + ": " + e.getMessage());
                if (e.abortsRun()) {
                    aborted = true;
                    log.warn("Windsor sync for @{} aborted in {}: {}", account.username(), name, e.getMessage());
                } else {
                    log.warn("Windsor sync step {} failed for @{}: {}", name, account.username(), e.getMessage());
                }
            } catch (RuntimeException e) {
                errors.add(name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("Windsor sync step {} failed for @{}", name, account.username(), e);
            }
        }

        List<JsonNode> rows(List<String> fields, LocalDate from, LocalDate to) {
            return client.rows(account.id(), fields, from, to).stream()
                    .filter(r -> WindsorMapper.belongsTo(r, account.igUserId())).toList();
        }
    }

    /** Profile, posts with insights, comments, daily metrics and audience. */
    public Outcome full(Account account) {
        Run run = new Run(account);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate from = today.minusDays(Math.max(1, props.historyDays()));

        run.step("profile", () -> profile(run, today));
        run.step("media", () -> media(run, from, today, now));
        run.step("comments", () -> comments(run, from, today, now));
        int lookback = daily.hasAny(account.id()) ? Math.min(sync.dailyLookbackDays(), props.historyDays())
                : props.historyDays();
        run.step("daily", () -> daily(run, today.minusDays(Math.max(1, lookback)), today));
        run.step("audience", () -> audience(run, now, today));
        return new Outcome(run.items, run.errors, run.aborted);
    }

    /** Live stories (Instagram keeps them 24 h) with their insights. */
    public Outcome stories(Account account) {
        Run run = new Run(account);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        run.step("stories", () -> {
            for (JsonNode r : run.rows(WindsorMapper.STORY_FIELDS, today.minusDays(1), today)) {
                MediaRow s = WindsorMapper.story(r);
                if (s.media().id() != null) {
                    long id = media.upsert(account.id(), s.media(), true, json.writeValueAsString(r));
                    snapshot(id, s, now);
                    run.items++;
                }
            }
        });
        return new Outcome(run.items, run.errors, run.aborted);
    }

    private void profile(Run run, LocalDate today) {
        List<JsonNode> rows = run.rows(WindsorMapper.PROFILE_FIELDS, today.minusDays(1), today);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Windsor returned no profile row for this account; "
                    + "check that it is still connected in Windsor.ai");
        }
        IgProfile p = WindsorMapper.profile(rows.getLast());
        accounts.updateProfile(run.account.id(), p);
        if (p.followersCount() != null) {
            daily.recordFollowers(run.account.id(), today, p.followersCount());
        }
        run.items++;
    }

    private void media(Run run, LocalDate from, LocalDate to, Instant now) {
        Map<String, MediaRow> latest = new LinkedHashMap<>();
        for (JsonNode r : run.rows(WindsorMapper.MEDIA_FIELDS, from, to)) {
            MediaRow m = WindsorMapper.media(r);
            if (m.media().id() != null) {
                latest.put(m.media().id(), m); // one row per post; the last one wins
            }
        }
        for (MediaRow m : latest.values()) {
            long id = media.upsert(run.account.id(), m.media(), false, json.writeValueAsString(m.media().raw()));
            snapshot(id, m, now);
            media.markInsightsFetched(id, now);
            run.items++;
        }
    }

    private void comments(Run run, LocalDate from, LocalDate to, Instant now) {
        Map<String, List<IgComment>> threads = WindsorMapper.commentThreads(
                run.rows(WindsorMapper.COMMENT_FIELDS, from, to));
        threads.forEach((igMediaId, list) -> {
            Optional<Long> mediaId = media.idFor(igMediaId);
            if (mediaId.isEmpty()) {
                return; // post outside the media window; picked up when it is synced
            }
            for (IgComment c : list) {
                run.items += comments.upsertThread(mediaId.get(), c);
            }
            media.markCommentsFetched(mediaId.get(), now);
        });
    }

    private void daily(Run run, LocalDate from, LocalDate to) {
        Map<LocalDate, Map<String, Long>> values = new TreeMap<>();
        Map<LocalDate, Long> newFollowers = new TreeMap<>();
        List<String> failed = new ArrayList<>();
        try {
            for (JsonNode r : run.rows(WindsorMapper.DAILY_FIELDS, from, to)) {
                LocalDate day = WindsorMapper.date(r, "date");
                if (day == null) {
                    continue;
                }
                Long reach = WindsorMapper.number(r, "reach");
                if (reach != null) {
                    values.computeIfAbsent(day, d -> new LinkedHashMap<>()).put("reach", reach);
                }
                Long follows = WindsorMapper.number(r, "follower_count");
                if (follows != null) {
                    newFollowers.put(day, follows);
                }
            }
        } catch (WindsorException e) {
            if (e.abortsRun()) {
                throw e;
            }
            failed.add("reach/follower_count (" + e.getMessage() + ")");
        }
        List<String> totals = new ArrayList<>(List.of("account_id", "date"));
        totals.addAll(WindsorMapper.DAILY_TOTAL_FIELDS.keySet());
        try {
            for (JsonNode r : run.rows(totals, from, to)) {
                LocalDate day = WindsorMapper.date(r, "date");
                if (day == null) {
                    continue;
                }
                WindsorMapper.DAILY_TOTAL_FIELDS.forEach((field, key) -> {
                    Long v = WindsorMapper.number(r, field);
                    if (v != null) {
                        values.computeIfAbsent(day, d -> new LinkedHashMap<>()).put(key, v);
                    }
                });
            }
        } catch (WindsorException e) {
            if (e.abortsRun()) {
                throw e;
            }
            failed.add("totals (" + e.getMessage() + ")");
        }
        java.util.Set<LocalDate> days = new java.util.TreeSet<>(values.keySet());
        days.addAll(newFollowers.keySet());
        for (LocalDate day : days) {
            String extra = null;
            if (newFollowers.containsKey(day)) {
                ObjectNode x = json.createObjectNode();
                x.put("follower_count", newFollowers.get(day));
                extra = x.toString();
            }
            daily.upsertMetrics(run.account.id(), day, values.getOrDefault(day, Map.of()), extra);
            run.items++;
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("daily metrics partly unavailable: " + failed);
        }
    }

    private void audience(Run run, Instant now, LocalDate today) {
        String metric = catalog.demographics().metric();
        Instant latest = audience.latestCapture(run.account.id(), metric).orElse(Instant.EPOCH);
        if (!latest.isBefore(today.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            return; // already captured today
        }
        List<String> failed = new ArrayList<>();
        for (String breakdown : catalog.demographics().breakdowns()) {
            List<String> fields = WindsorMapper.AUDIENCE_FIELDS.get(breakdown);
            if (fields == null) {
                continue;
            }
            try {
                List<String> request = new ArrayList<>(List.of("account_id"));
                request.addAll(fields);
                Map<String, Long> values = WindsorMapper.audience(run.rows(request, today.minusDays(1), today),
                        fields.get(0), fields.get(1));
                if (!values.isEmpty()) {
                    audience.insert(run.account.id(), now, metric, breakdown, values);
                    run.items += values.size();
                }
            } catch (WindsorException e) {
                if (e.abortsRun()) {
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

    private void snapshot(long mediaId, MediaRow m, Instant at) {
        Map<String, Long> v = m.metrics();
        String extra = null;
        if (!m.extra().isEmpty()) {
            ObjectNode x = json.createObjectNode();
            m.extra().forEach(x::put);
            extra = x.toString();
        }
        if (v.isEmpty() && extra == null) {
            return;
        }
        insights.insert(mediaId, new Snapshot(at, v.get("reach"), v.get("views"), v.get("likes"), v.get("comments"),
                v.get("shares"), v.get("saved"), v.get("follows"), v.get("total_interactions"),
                v.get("profile_visits"), v.get("ig_reels_avg_watch_time"), extra));
    }
}
