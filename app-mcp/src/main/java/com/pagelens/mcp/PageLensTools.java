package com.pagelens.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pagelens.core.analysis.AnalysisModels.Metric;
import com.pagelens.core.analysis.AnalysisModels.PostStat;
import com.pagelens.core.analysis.AnalysisModels.Range;
import com.pagelens.core.analysis.AnalysisService;
import com.pagelens.core.analysis.Analytics;
import com.pagelens.core.auth.TokenService;
import com.pagelens.core.config.MetricCatalog;
import com.pagelens.core.instagram.ApiUsage;
import com.pagelens.core.instagram.RateLimitGuard;
import com.pagelens.core.instagram.SecretMasker;
import com.pagelens.core.logging.CorrelationId;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AudienceRepository;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.CommentRepository;
import com.pagelens.core.repo.CommentRepository.Comment;
import com.pagelens.core.repo.InsightRepository;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.repo.MediaRepository.Post;
import com.pagelens.core.repo.MediaRepository.PostQuery;
import com.pagelens.core.repo.SyncRunRepository;
import com.pagelens.core.repo.SyncRunRepository.SyncRun;
import com.pagelens.core.sync.SyncService;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * PageLens MCP tools (plan section 9). Read tools only touch the local database, never Instagram. Results are
 * compact JSON. Every call is logged with its own correlation id (to stderr and the log files, never stdout).
 */
@Component
public class PageLensTools {

    private static final Logger log = LoggerFactory.getLogger(PageLensTools.class);
    private static final List<String> METRICS = java.util.Arrays.stream(Metric.values()).map(Metric::key).toList();
    private static final ToolAnnotations READ_ONLY = new ToolAnnotations(null, true, false, true, false, null);

    /** Tool calls currently executing; shutdown waits for these after the client closes stdin. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();

    private final AnalysisService analysis;
    private final AccountRepository accounts;
    private final MediaRepository media;
    private final InsightRepository insights;
    private final CommentRepository comments;
    private final AudienceRepository audience;
    private final SyncRunRepository runs;
    private final TokenService tokens;
    private final RateLimitGuard rateLimit;
    private final SyncService sync;
    private final AuditLogRepository audit;
    private final MetricCatalog catalog;
    private final McpProperties props;

    public PageLensTools(AnalysisService analysis, AccountRepository accounts, MediaRepository media,
                         InsightRepository insights, CommentRepository comments, AudienceRepository audience,
                         SyncRunRepository runs, TokenService tokens, RateLimitGuard rateLimit, SyncService sync,
                         AuditLogRepository audit, MetricCatalog catalog, McpProperties props) {
        this.analysis = analysis;
        this.accounts = accounts;
        this.media = media;
        this.insights = insights;
        this.comments = comments;
        this.audience = audience;
        this.runs = runs;
        this.tokens = tokens;
        this.rateLimit = rateLimit;
        this.sync = sync;
        this.audit = audit;
        this.catalog = catalog;
        this.props = props;
    }

    McpViews views() {
        return new McpViews(analysis.zone());
    }

    public List<SyncToolSpecification> specifications() {
        List<SyncToolSpecification> tools = new ArrayList<>();

        tools.add(tool("list_accounts", "List connected Instagram accounts",
                "Lists the connected Instagram accounts with follower count, connection status, token expiry and "
                        + "last sync time. Start here to learn account names for the other tools.",
                new ToolArgs.Schema(), a -> listAccounts()));

        tools.add(tool("get_account_overview", "Account overview",
                "Headline numbers for one account: followers and their 7/30-day change, reach and views over the "
                        + "last 7 completed days vs the 7 before, posts and average engagement rate in the last 30 days, "
                        + "best and worst recent posts by reach, last sync and token status.",
                new ToolArgs.Schema().account(), this::overview));

        tools.add(tool("list_posts", "List posts",
                "Lists posts (newest first by default) with their latest metrics. Use the returned postId with "
                        + "get_post_details or get_post_comments.",
                new ToolArgs.Schema().account()
                        .prop("from", "string", "Posted on or after this date, YYYY-MM-DD (owner's time zone).")
                        .prop("to", "string", "Posted on or before this date, YYYY-MM-DD (owner's time zone).")
                        .enumProp("type", List.of("REELS", "FEED", "CAROUSEL_ALBUM", "IMAGE", "VIDEO", "STORY"),
                                "Only this format.")
                        .enumProp("sort_by", new ArrayList<>(MediaRepository.SORT_KEYS), "Sort key (default posted_at).")
                        .enumProp("order", List.of("desc", "asc"), "Sort order (default desc).")
                        .prop("limit", "integer", "Max posts to return (default 20, max 100)."),
                this::listPosts));

        tools.add(tool("get_post_details", "Post details",
                "One post with all latest metrics (including Reels watch time and any extra metrics Instagram "
                        + "returned) and its insight snapshot history, to see how it performed over time.",
                new ToolArgs.Schema().prop("post_id", "integer", "PageLens post id from list_posts.").required("post_id"),
                this::postDetails));

        tools.add(tool("get_post_comments", "Post comments",
                "Comment threads on a post (newest first), each with its replies. Comments are synced for posts "
                        + "from the last 14 days.",
                new ToolArgs.Schema().prop("post_id", "integer", "PageLens post id from list_posts.").required("post_id")
                        .prop("limit", "integer", "Max top-level comments (default 50, max 200)."),
                this::postComments));

        String rankDesc = " by a metric over a period. Posts where Instagram returned no value for the metric are "
                + "excluded and counted in excludedNoData. engagement_rate = interactions / reach.";
        ToolArgs.Schema rankSchema = new ToolArgs.Schema().account()
                .enumProp("metric", METRICS, "Metric to rank by (default reach).")
                .prop("count", "integer", "How many posts (default 5, max 50).").period(AnalysisService.DEFAULT_PERIOD);
        tools.add(tool("get_top_posts", "Top posts", "Best-performing posts" + rankDesc, rankSchema,
                a -> ranked(a, false)));
        tools.add(tool("get_bottom_posts", "Bottom posts", "Worst-performing posts" + rankDesc, rankSchema,
                a -> ranked(a, true)));

        tools.add(tool("compare_formats", "Compare formats",
                "Compares Reels, carousels, images and feed videos: post count, average and median reach, average "
                        + "views, interactions, saves, shares and engagement rate. Formats with few posts are flagged "
                        + "smallSample.",
                new ToolArgs.Schema().account().period(AnalysisService.DEFAULT_PERIOD), this::formats));

        tools.add(tool("get_best_posting_times", "Best posting times",
                "Average performance of past posts by weekday and hour in the owner's time zone, with the top "
                        + "slots (each backed by at least 2 posts). Shows correlation in past data, not causation.",
                new ToolArgs.Schema().account()
                        .enumProp("metric", METRICS, "Metric to average (default engagement_rate).")
                        .period(AnalysisService.DEFAULT_PERIOD),
                this::postingTimes));

        tools.add(tool("get_audience_breakdown", "Audience breakdown",
                "Follower demographics from the latest snapshot: country, city, age or gender (all four if "
                        + "dimension is omitted). Instagram returns at most the top 45 groups and nothing for "
                        + "accounts under 100 followers. Groups under 30 people are flagged smallSample.",
                new ToolArgs.Schema().account()
                        .enumProp("dimension", catalog.demographics().breakdowns(), "One dimension, or omit for all.")
                        .prop("limit", "integer", "Max groups per dimension (default 15, max 45)."),
                this::audience));

        tools.add(tool("get_follower_growth", "Follower growth",
                "Follower count over time (mode daily: one point per synced UTC day), or per post (mode per_post: "
                        + "follows Instagram attributes to each post plus the account's net change that day).",
                new ToolArgs.Schema().account()
                        .enumProp("mode", List.of("daily", "per_post"), "Default daily.")
                        .period("last_30_days")
                        .prop("limit", "integer", "Max rows (default 60, max 400)."),
                this::growth));

        tools.add(tool("get_caption_stats", "Caption statistics",
                "Average reach and engagement rate grouped by caption length, script (farsi/armenian/latin/mixed), "
                        + "hashtag count and call-to-action presence.",
                new ToolArgs.Schema().account().period(AnalysisService.DEFAULT_PERIOD), this::captions));

        tools.add(tool("get_sync_status", "Sync status",
                "Recent sync runs (status, items fetched, errors), last successful sync, token health and current "
                        + "Instagram rate-limit usage. Use it to judge how fresh the data is.",
                new ToolArgs.Schema().account().prop("limit", "integer", "Max runs (default 10, max 50)."),
                this::syncStatus));

        if (props.syncToolEnabled()) {
            tools.add(new SyncToolSpecification(Tool.builder()
                    .name("sync_now").title("Sync now")
                    .description("Starts a background sync from Instagram for one account and returns its run id; "
                            + "poll get_sync_status for the result. Reads from Instagram only (nothing is posted "
                            + "or changed there) but spends API rate budget, so don't call it repeatedly. "
                            + "job FULL refreshes everything; STORIES only live stories.")
                    .inputSchema(new ToolArgs.Schema().account()
                            .enumProp("job", List.of("FULL", "STORIES"), "Default FULL.").build())
                    .annotations(new ToolAnnotations(null, false, false, false, true, null))
                    .build(), (exchange, req) -> call("sync_now", req.arguments(), this::syncNow)));
        }
        return tools;
    }

    /** Same content as list_accounts, for the pagelens://accounts resource. */
    String accountsJson() {
        return views().toJson(listAccounts());
    }

    // ------------------------------------------------------------------------------------------ handlers

    private Object listAccounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        McpViews v = views();
        for (Account a : accounts.findAll()) {
            TokenService.TokenHealth t = tokens.health(a.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", a.id());
            m.put("username", a.username());
            m.put("displayName", a.displayName());
            m.put("loginType", a.loginType());
            m.put("status", a.status());
            m.put("statusMessage", a.statusMessage());
            m.put("followers", a.followersCount());
            m.put("mediaCount", a.mediaCount());
            m.put("lastSyncedAt", v.time(a.lastSyncedAt()));
            m.put("tokenStatus", t.status());
            m.put("tokenDaysLeft", t.daysLeft());
            out.add(m);
        }
        return Map.of("timeZone", analysis.zone().getId(), "accounts", out);
    }

    private Object overview(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        var o = analysis.overview(acc.id());
        McpViews v = views();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", "@" + acc.username());
        m.put("followers", acc.followersCount());
        m.put("followersChange7d", o.followersChange7d());
        m.put("followersChange30d", o.followersChange30d());
        m.put("reachLast7d", o.reachLast7d());
        m.put("reachPrevious7d", o.reachPrevious7d());
        m.put("viewsLast7d", o.viewsLast7d());
        m.put("viewsPrevious7d", o.viewsPrevious7d());
        m.put("postsLast30d", o.postsLast30d());
        m.put("avgEngagementRateLast30d", o.avgEngagementRateLast30d());
        m.put("bestRecentByReach", v.posts(o.bestRecent()));
        m.put("worstRecentByReach", v.posts(o.worstRecent()));
        m.put("lastSyncedAt", v.time(acc.lastSyncedAt()));
        m.put("lastRun", o.lastRun() == null ? null : run(o.lastRun(), v));
        m.put("token", Map.of("status", o.token().status(), "expiringSoon", o.token().expiringSoon()));
        m.put("notes", "Reach sums daily reach (not de-duplicated across days). Follower changes come from "
                + "PageLens's daily snapshots and need at least that many days of history.");
        return m;
    }

    private Object listPosts(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        String sort = a.str("sort_by") == null ? "posted_at" : a.str("sort_by");
        if (!MediaRepository.SORT_KEYS.contains(sort)) {
            throw new IllegalArgumentException("sort_by must be one of " + MediaRepository.SORT_KEYS);
        }
        Range r = a.str("from") == null && a.str("to") == null ? null : analysis.range(null, a.str("from"), a.str("to"));
        int limit = a.integer("limit", 20, 1, 100);
        var page = media.query(new PostQuery(acc.id(), r == null ? null : r.from(), r == null ? null : r.to(),
                a.str("type"), sort, "asc".equalsIgnoreCase(a.str("order")), limit, 0));
        McpViews v = views();
        return Map.of("account", "@" + acc.username(), "total", page.total(), "returned", page.items().size(),
                "posts", page.items().stream().map(v::postRow).toList());
    }

    private Object postDetails(ToolArgs a) {
        long id = a.requiredLong("post_id");
        Post p = media.findPost(id).orElseThrow(() -> new IllegalArgumentException("No post with id " + id));
        McpViews v = views();
        PostStat stat = Analytics.stat(p);
        Map<String, Object> m = new LinkedHashMap<>(v.post(stat));
        m.put("caption", p.caption()); // full caption here
        m.put("type", p.productType());
        m.put("mediaType", p.mediaType());
        if (p.latest() != null) {
            m.put("latest", v.snapshot(p.latest()));
            m.put("extraMetrics", p.latest().extraMetrics());
        }
        var history = insights.history(id);
        m.put("snapshotCount", history.size());
        m.put("history", McpViews.downsample(history, 20).stream().map(v::snapshot).toList());
        return m;
    }

    private Object postComments(ToolArgs a) {
        long id = a.requiredLong("post_id");
        media.findPost(id).orElseThrow(() -> new IllegalArgumentException("No post with id " + id));
        McpViews v = views();
        List<Map<String, Object>> threads = new ArrayList<>();
        for (Comment c : comments.threads(id, a.integer("limit", 50, 1, 200))) {
            Map<String, Object> t = comment(c, v);
            t.put("replies", c.replies().stream().map(r -> comment(r, v)).toList());
            threads.add(t);
        }
        return Map.of("postId", id, "threads", threads);
    }

    private static Map<String, Object> comment(Comment c, McpViews v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", c.username());
        m.put("text", c.text());
        m.put("likes", c.likeCount());
        m.put("postedAt", v.time(c.postedAt()));
        if (c.hidden()) {
            m.put("hidden", true);
        }
        return m;
    }

    private Object ranked(ToolArgs a, boolean bottom) {
        Account acc = analysis.resolveAccount(a.str("account"));
        Range r = analysis.range(a.str("period"), a.str("from"), a.str("to"));
        var res = analysis.ranked(acc.id(), Metric.parse(a.str("metric")), a.integer("count", 5, 1, 50), bottom, r);
        McpViews v = views();
        return Map.of("account", "@" + acc.username(), "metric", res.metric().key(), "order", res.order(),
                "period", period(r, v), "postsInPeriod", res.consideredPosts(), "excludedNoData", res.excludedNoData(),
                "posts", v.posts(res.posts()));
    }

    private Object formats(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        Range r = analysis.range(a.str("period"), a.str("from"), a.str("to"));
        var res = analysis.formats(acc.id(), r);
        return Map.of("account", "@" + acc.username(), "period", period(r, views()), "formats", res.formats(),
                "notes", res.note());
    }

    private Object postingTimes(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        Range r = analysis.range(a.str("period"), a.str("from"), a.str("to"));
        String metric = a.str("metric") == null ? "engagement_rate" : a.str("metric");
        var res = analysis.postingTimes(acc.id(), Metric.parse(metric), r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", "@" + acc.username());
        m.put("metric", res.metric().key());
        m.put("timeZone", res.timeZone());
        m.put("period", period(r, views()));
        m.put("postsCounted", res.posts());
        m.put("bestSlots", res.bestSlots());
        m.put("byWeekday", res.byWeekday());
        m.put("byHour", res.byHour());
        m.put("cells", res.cells());
        m.put("notes", res.note());
        return m;
    }

    private Object audience(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        String dim = a.str("dimension");
        List<String> dims = dim == null ? catalog.demographics().breakdowns() : List.of(dim.toLowerCase());
        int limit = a.integer("limit", 15, 1, 45);
        McpViews v = views();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String d : dims) {
            audience.latest(acc.id(), catalog.demographics().metric(), d).ifPresent(b -> {
                long total = b.entries().stream().mapToLong(AudienceRepository.AudienceEntry::value).sum();
                out.add(Map.of("dimension", d, "capturedAt", v.time(b.capturedAt()), "totalInTopGroups", total,
                        "groups", b.entries().stream().limit(limit).map(e -> Map.of("key", e.key(), "value", e.value(),
                                "share", total == 0 ? 0 : Math.round(e.value() * 1000d / total) / 1000d,
                                "smallSample", e.smallSample())).toList()));
            });
        }
        return Map.of("account", "@" + acc.username(), "breakdowns", out, "notes",
                out.isEmpty() ? "No audience data yet: Instagram returns none under 100 followers, or no sync has run."
                        : "share is relative to the top groups Instagram returned (max 45), not all followers.");
    }

    private Object growth(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        Range r = analysis.range(a.str("period"), a.str("from"), a.str("to"), "last_30_days");
        var g = analysis.growth(acc.id(), r);
        int limit = a.integer("limit", 60, 1, 400);
        McpViews v = views();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", "@" + acc.username());
        m.put("period", period(r, v));
        m.put("startFollowers", g.startFollowers());
        m.put("endFollowers", g.endFollowers());
        m.put("netChange", g.netChange());
        if ("per_post".equalsIgnoreCase(a.str("mode"))) {
            m.put("perPost", g.perPost().stream().limit(limit).map(pg -> {
                Map<String, Object> row = new LinkedHashMap<>(v.post(pg.post()));
                row.remove("metrics");
                row.put("followsFromPost", pg.followsFromPost());
                row.put("followerDeltaThatDay", pg.followerDeltaThatDay());
                return row;
            }).toList());
        } else {
            m.put("daily", McpViews.downsample(g.daily(), limit));
        }
        m.put("notes", g.note());
        return m;
    }

    private Object captions(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        Range r = analysis.range(a.str("period"), a.str("from"), a.str("to"));
        var c = analysis.captions(acc.id(), r);
        return Map.of("account", "@" + acc.username(), "period", period(r, views()), "posts", c.posts(),
                "buckets", c.buckets(), "notes", c.note());
    }

    private Object syncStatus(ToolArgs a) {
        McpViews v = views();
        Account acc = a.str("account") == null && accounts.findAll().size() != 1 ? null
                : analysis.resolveAccount(a.str("account"));
        int limit = a.integer("limit", 10, 1, 50);
        Map<String, Object> m = new LinkedHashMap<>();
        if (acc != null) {
            TokenService.TokenHealth t = tokens.health(acc.id());
            m.put("account", "@" + acc.username());
            m.put("status", acc.status());
            m.put("statusMessage", acc.statusMessage());
            m.put("lastSyncedAt", v.time(acc.lastSyncedAt()));
            m.put("token", Map.of("status", t.status(), "daysLeft", t.daysLeft() == null ? "unknown" : t.daysLeft(),
                    "expiringSoon", t.expiringSoon()));
            rateLimit.current(acc.id()).ifPresent(u -> m.put("rateLimit", usage(u, v)));
        }
        m.put("runs", runs.recent(acc == null ? null : acc.id(), limit).stream().map(r -> run(r, v)).toList());
        return m;
    }

    private Object syncNow(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        String job = a.str("job") == null ? "FULL" : a.str("job").toUpperCase();
        if (!List.of("FULL", "STORIES").contains(job)) {
            throw new IllegalArgumentException("job must be FULL or STORIES");
        }
        long runId;
        try {
            runId = sync.startAsync(acc.id(), SyncService.JobType.valueOf(job), SyncService.Trigger.MCP);
        } catch (SyncService.AlreadyRunningException e) {
            audit.record(Actor.MCP, "sync.start", "@" + acc.username(), job, false, "already_running");
            return Map.of("started", false, "reason", e.getMessage());
        }
        audit.record(Actor.MCP, "sync.start", "@" + acc.username(), job + " run " + runId, false, "ok");
        return Map.of("started", true, "runId", runId, "next",
                "Call get_sync_status in a minute or two; a first full sync can take several minutes.");
    }

    // ------------------------------------------------------------------------------------------ plumbing

    private static Map<String, Object> period(Range r, McpViews v) {
        return Map.of("label", r.label(), "from", v.time(r.from()), "to", v.time(r.to()));
    }

    private static Map<String, Object> run(SyncRun r, McpViews v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.id());
        m.put("account", r.accountUsername() == null ? null : "@" + r.accountUsername());
        m.put("job", r.jobType());
        m.put("triggeredBy", r.triggeredBy());
        m.put("status", r.status());
        m.put("startedAt", v.time(r.startedAt()));
        m.put("finishedAt", v.time(r.finishedAt()));
        m.put("itemsFetched", r.itemsFetched());
        m.put("errors", r.errorMessage());
        return m;
    }

    private static Map<String, Object> usage(ApiUsage u, McpViews v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usagePercent", u.percent());
        m.put("blockedUntil", v.time(u.regainAt()));
        return m;
    }

    private SyncToolSpecification tool(String name, String title, String description, ToolArgs.Schema schema,
                                       Function<ToolArgs, Object> handler) {
        Tool tool = Tool.builder().name(name).title(title).description(description).inputSchema(schema.build())
                .annotations(READ_ONLY).build();
        return new SyncToolSpecification(tool, (exchange, req) -> call(name, req.arguments(), handler));
    }

    /** Runs a tool with a correlation id; turns bad input into a tool error Claude can read and correct. */
    CallToolResult call(String name, Map<String, Object> arguments, Function<ToolArgs, Object> handler) {
        inFlight.incrementAndGet();
        try (var ignored = CorrelationId.open("mcp")) {
            long started = System.nanoTime();
            try {
                Object result = handler.apply(new ToolArgs(arguments));
                String text = views().toJson(result);
                log.info("MCP tool {} ok in {} ms ({} chars)", name, (System.nanoTime() - started) / 1_000_000, text.length());
                return CallToolResult.builder().addTextContent(text).isError(false).build();
            } catch (IllegalArgumentException e) {
                log.info("MCP tool {} rejected input: {}", name, e.getMessage());
                return CallToolResult.builder().addTextContent("Invalid input: " + e.getMessage()).isError(true).build();
            } catch (RuntimeException e) {
                log.error("MCP tool {} failed", name, e);
                return CallToolResult.builder()
                        .addTextContent("PageLens error: " + SecretMasker.mask(String.valueOf(e.getMessage()))
                                + " (details in the PageLens logs, correlation id " + CorrelationId.current() + ")")
                        .isError(true).build();
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** Waits (bounded) until no tool call is executing, so answers already being computed still get sent. */
    void awaitIdle(java.time.Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        Thread.sleep(300); // let requests read just before EOF reach their handler
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(100); // and let the last response flush
    }
}
