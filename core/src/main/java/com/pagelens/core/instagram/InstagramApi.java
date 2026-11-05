package com.pagelens.core.instagram;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pagelens.core.instagram.GraphApiException.Kind;
import com.pagelens.core.instagram.IgModels.AccountRef;
import com.pagelens.core.instagram.IgModels.IgComment;
import com.pagelens.core.instagram.IgModels.IgMedia;
import com.pagelens.core.instagram.IgModels.IgProfile;
import com.pagelens.core.instagram.IgModels.Insights;

import tools.jackson.databind.JsonNode;

/**
 * Typed read operations on the Instagram Graph API, for both login setups. Endpoint, field and metric facts
 * come from docs/API_NOTES.md (verified 2026-11-05). Where Meta's docs are unclear, requests degrade
 * gracefully: rejected fields are dropped, and rejected metrics are retried one by one and reported as
 * unsupported.
 */
@Component
public class InstagramApi {

    private static final Logger log = LoggerFactory.getLogger(InstagramApi.class);

    static final String MEDIA_FIELDS =
            "id,caption,media_type,media_product_type,permalink,thumbnail_url,media_url,timestamp,like_count,comments_count,shortcode";
    static final String MEDIA_FIELDS_MINIMAL = "id,media_type,permalink,thumbnail_url,timestamp,like_count,comments_count";
    static final String COMMENT_FIELDS = "id,text,timestamp,username,like_count,hidden";
    static final String COMMENT_FIELDS_MINIMAL = "id,text,timestamp,username";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_COMMENT_PAGES = 20;
    private static final DateTimeFormatter IG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final GraphApiClient client;

    public InstagramApi(GraphApiClient client) {
        this.client = client;
    }

    // ---------------------------------------------------------------- profile

    public IgProfile profile(AccountRef a) {
        boolean ig = a.loginType() == LoginType.INSTAGRAM_LOGIN;
        // Instagram Login: /me returns user_id = the IG professional account id; id is app-scoped only.
        String path = ig ? "/me" : "/" + a.igUserId();
        String fields = ig
                ? "user_id,username,name,account_type,profile_picture_url,followers_count,follows_count,media_count"
                : "id,username,name,profile_picture_url,followers_count,follows_count,media_count";
        String minimal = ig ? "user_id,username,followers_count,media_count" : "id,username,followers_count,media_count";
        JsonNode n = withFieldFallback(a, path, fields, minimal, Map.of());
        String igId = ig ? text(n, "user_id") : text(n, "id");
        return new IgProfile(igId != null ? igId : a.igUserId(), text(n, "username"), text(n, "name"),
                text(n, "account_type"), text(n, "profile_picture_url"), num(n, "followers_count"),
                num(n, "follows_count"), num(n, "media_count"));
    }

    // ---------------------------------------------------------------- media

    /**
     * Newest-first media, paging until {@code maxItems} (≤0 = no cap besides Meta's 10K) or until
     * {@code keepGoing} returns false for an item.
     */
    public List<IgMedia> media(AccountRef a, int maxItems, Predicate<IgMedia> keepGoing) {
        List<IgMedia> out = new ArrayList<>();
        JsonNode page = withFieldFallback(a, "/" + a.igUserId() + "/media", MEDIA_FIELDS, MEDIA_FIELDS_MINIMAL,
                Map.of("limit", String.valueOf(PAGE_SIZE)));
        while (true) {
            JsonNode data = page.path("data");
            for (int i = 0; i < data.size(); i++) {
                IgMedia m = toMedia(data.get(i));
                out.add(m);
                if ((maxItems > 0 && out.size() >= maxItems) || !keepGoing.test(m)) {
                    return out;
                }
            }
            String next = page.path("paging").path("next").asString(null);
            if (next == null || data.isEmpty()) {
                return out;
            }
            page = client.getAbsolute(a.accountId(), next);
        }
    }

    /** Currently live stories (24 h). Meta documents this edge for Facebook Login; Instagram Login may refuse it. */
    public List<IgMedia> stories(AccountRef a) {
        JsonNode page = withFieldFallback(a, "/" + a.igUserId() + "/stories", MEDIA_FIELDS, MEDIA_FIELDS_MINIMAL,
                Map.of());
        List<IgMedia> out = new ArrayList<>();
        JsonNode data = page.path("data");
        for (int i = 0; i < data.size(); i++) {
            IgMedia m = toMedia(data.get(i));
            out.add(new IgMedia(m.id(), m.caption(), m.mediaType(), "STORY", m.permalink(), m.thumbnailUrl(),
                    m.mediaUrl(), m.timestamp(), m.likeCount(), m.commentsCount(), m.shortcode(), m.raw()));
        }
        return out;
    }

    // ---------------------------------------------------------------- insights

    /** Lifetime insights for one media item. */
    public Insights mediaInsights(AccountRef a, String mediaId, List<String> metrics) {
        return insights(a, "/" + mediaId + "/insights", metrics, Map.of());
    }

    /** Account insights for one UTC day ({@code period=day&metric_type=total_value}). */
    public Insights accountDay(AccountRef a, LocalDate utcDay, List<String> metrics) {
        long since = utcDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long until = utcDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return insights(a, "/" + a.igUserId() + "/insights", metrics, Map.of(
                "period", "day", "metric_type", "total_value",
                "since", String.valueOf(since), "until", String.valueOf(until)));
    }

    /** One account metric for one UTC day with a breakdown, e.g. follows_and_unfollows by follow_type. */
    public Map<String, Long> accountDayBreakdown(AccountRef a, LocalDate utcDay, String metric, String breakdown) {
        long since = utcDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long until = utcDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        JsonNode body = client.get(a.accountId(), client.graphBase(a.loginType()) + "/" + a.igUserId() + "/insights",
                params(a, Map.of("metric", metric, "period", "day", "metric_type", "total_value",
                        "breakdown", breakdown, "since", String.valueOf(since), "until", String.valueOf(until))));
        return breakdownResults(body);
    }

    /** Audience demographics ({@code follower_demographics}), top 45 per breakdown, key → count. */
    public Map<String, Long> demographics(AccountRef a, String metric, String timeframe, String breakdown) {
        JsonNode body = client.get(a.accountId(), client.graphBase(a.loginType()) + "/" + a.igUserId() + "/insights",
                params(a, Map.of("metric", metric, "period", "lifetime", "metric_type", "total_value",
                        "timeframe", timeframe, "breakdown", breakdown)));
        return breakdownResults(body);
    }

    private Insights insights(AccountRef a, String path, List<String> metrics, Map<String, String> extra) {
        String url = client.graphBase(a.loginType()) + path;
        Map<String, Long> values = new LinkedHashMap<>();
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        Set<String> unsupported = new LinkedHashSet<>();
        try {
            collect(fetchInsights(a, url, String.join(",", metrics), extra), values, raw);
        } catch (GraphApiException e) {
            if (e.kind() != Kind.INVALID_REQUEST || metrics.size() == 1) {
                throw e;
            }
            // One bad metric fails the whole request: retry individually to find which.
            for (String metric : metrics) {
                try {
                    collect(fetchInsights(a, url, metric, extra), values, raw);
                } catch (GraphApiException single) {
                    if (single.kind() != Kind.INVALID_REQUEST) {
                        throw single;
                    }
                    unsupported.add(metric);
                }
            }
            if (!unsupported.isEmpty()) {
                log.warn("Metrics rejected by the API for {}: {} (skipped, not stored as 0)", path, unsupported);
            }
        }
        return new Insights(values, raw, unsupported);
    }

    private JsonNode fetchInsights(AccountRef a, String url, String metricList, Map<String, String> extra) {
        Map<String, String> p = new LinkedHashMap<>(extra);
        p.put("metric", metricList);
        return client.get(a.accountId(), url, params(a, p));
    }

    private static void collect(JsonNode body, Map<String, Long> values, Map<String, JsonNode> raw) {
        JsonNode data = body.path("data");
        for (int i = 0; i < data.size(); i++) {
            JsonNode metric = data.get(i);
            String name = metric.path("name").asString(null);
            if (name == null) {
                continue;
            }
            raw.put(name, metric);
            Long value = null;
            JsonNode total = metric.path("total_value").path("value");
            JsonNode first = metric.path("values").path(0).path("value");
            if (total.isNumber()) {
                value = total.asLong();
            } else if (first.isNumber()) {
                value = first.asLong();
            }
            if (value != null) {
                values.put(name, value);
            }
        }
    }

    private static Map<String, Long> breakdownResults(JsonNode body) {
        Map<String, Long> out = new LinkedHashMap<>();
        JsonNode data = body.path("data");
        for (int i = 0; i < data.size(); i++) {
            JsonNode breakdowns = data.get(i).path("total_value").path("breakdowns");
            for (int b = 0; b < breakdowns.size(); b++) {
                JsonNode results = breakdowns.get(b).path("results");
                for (int r = 0; r < results.size(); r++) {
                    JsonNode res = results.get(r);
                    JsonNode dims = res.path("dimension_values");
                    List<String> parts = new ArrayList<>();
                    for (int d = 0; d < dims.size(); d++) {
                        parts.add(dims.get(d).asString(""));
                    }
                    if (!parts.isEmpty() && res.path("value").isNumber()) {
                        out.merge(String.join("|", parts), res.path("value").asLong(), Long::sum);
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- comments

    /** Top-level comments with replies, newest first (Meta returns at most 50 per page). */
    public List<IgComment> comments(AccountRef a, String mediaId) {
        String fields = COMMENT_FIELDS + ",replies{" + COMMENT_FIELDS + "}";
        String minimal = COMMENT_FIELDS_MINIMAL + ",replies{" + COMMENT_FIELDS_MINIMAL + "}";
        JsonNode page = withFieldFallback(a, "/" + mediaId + "/comments", fields, minimal, Map.of());
        List<IgComment> out = new ArrayList<>();
        for (int pages = 0; pages < MAX_COMMENT_PAGES; pages++) {
            JsonNode data = page.path("data");
            for (int i = 0; i < data.size(); i++) {
                out.add(toComment(data.get(i)));
            }
            String next = page.path("paging").path("next").asString(null);
            if (next == null || data.isEmpty()) {
                break;
            }
            page = client.getAbsolute(a.accountId(), next);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** GET with {@code fields}; if Meta rejects a field (code 100), retry once with {@code minimalFields}. */
    private JsonNode withFieldFallback(AccountRef a, String path, String fields, String minimalFields,
                                       Map<String, String> extra) {
        String url = client.graphBase(a.loginType()) + path;
        Map<String, String> p = new LinkedHashMap<>(extra);
        p.put("fields", fields);
        try {
            return client.get(a.accountId(), url, params(a, p));
        } catch (GraphApiException e) {
            if (e.kind() != Kind.INVALID_REQUEST || minimalFields == null) {
                throw e;
            }
            log.warn("Fields rejected for {} ({}); retrying with minimal fields", path, e.getMessage());
            p.put("fields", minimalFields);
            return client.get(a.accountId(), url, params(a, p));
        }
    }

    private static Map<String, String> params(AccountRef a, Map<String, String> p) {
        Map<String, String> out = new LinkedHashMap<>(p);
        out.put("access_token", a.accessToken());
        return out;
    }

    public static IgMedia toMedia(JsonNode n) {
        return new IgMedia(text(n, "id"), text(n, "caption"), text(n, "media_type"), text(n, "media_product_type"),
                text(n, "permalink"), text(n, "thumbnail_url"), text(n, "media_url"), timestamp(n, "timestamp"),
                num(n, "like_count"), num(n, "comments_count"), text(n, "shortcode"), n);
    }

    static IgComment toComment(JsonNode n) {
        List<IgComment> replies = new ArrayList<>();
        JsonNode data = n.path("replies").path("data");
        for (int i = 0; i < data.size(); i++) {
            replies.add(toComment(data.get(i)));
        }
        JsonNode hidden = n.path("hidden");
        return new IgComment(text(n, "id"), text(n, "text"), timestamp(n, "timestamp"), text(n, "username"),
                num(n, "like_count"), hidden.isBoolean() ? hidden.asBoolean() : null, replies);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    static Long num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() ? v.asLong() : null;
    }

    /** Parses Meta's {@code 2026-11-01T10:00:00+0000} (and ISO-8601 with a colon, to be safe). */
    static Instant timestamp(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s, IG_TIMESTAMP).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
