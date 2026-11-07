package com.pagelens.core.windsor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pagelens.core.instagram.IgModels.IgComment;
import com.pagelens.core.instagram.IgModels.IgMedia;
import com.pagelens.core.instagram.IgModels.IgProfile;

import tools.jackson.databind.JsonNode;

/**
 * Pure mapping from Windsor.ai Instagram rows to PageLens' Graph-shaped models, so the existing repositories
 * store them unchanged. Field ids come from {@code https://connectors.windsor.ai/instagram/fields} (checked
 * 2026-11-06). Each request stays within one of Windsor's field tables (user_info, user_insights_day,
 * media_info + media_insights, comments, stories, one audience breakdown), so Windsor never has to join tables.
 */
public final class WindsorMapper {

    public static final List<String> PROFILE_FIELDS = List.of("account_id", "username", "name", "followers_count",
            "follows_count", "media_count");

    /** user_insights_day: daily reach and new followers. */
    public static final List<String> DAILY_FIELDS = List.of("account_id", "date", "reach", "follower_count");

    /** user_insights_day_total_value. Windsor field id → PageLens daily column key. */
    public static final Map<String, String> DAILY_TOTAL_FIELDS = orderedMap(
            "views", "views", "accounts_engaged", "accounts_engaged", "total_interactions", "total_interactions",
            "likes", "likes", "comments", "comments", "shares", "shares", "saves", "saves", "replies", "replies",
            "profile_links_taps", "profile_links_taps");

    public static final List<String> MEDIA_FIELDS = List.of("account_id", "media_id", "media_caption",
            "media_permalink", "media_type", "media_product_type", "timestamp", "media_like_count",
            "media_comments_count", "media_shortcode", "media_thumbnail_url", "media_url", "media_reach",
            "media_views", "media_saved", "media_shares", "media_engagement", "media_follows",
            "media_profile_visits", "media_reel_avg_watch_time", "media_reel_total_watch_time",
            "media_reel_skip_rate", "media_reposts");

    public static final List<String> COMMENT_FIELDS = List.of("account_id", "media_id", "comment_id", "comment_text",
            "comment_timestamp", "comment_username", "comment_like_count", "comment_parent_id");

    public static final List<String> STORY_FIELDS = List.of("account_id", "story_id", "story_timestamp",
            "story_permalink", "story_thumbnail_url", "story_views", "story_reach", "story_replies", "story_shares",
            "story_interactions", "story_exits", "story_taps_forward", "story_taps_back", "story_swipe_forward");

    /** PageLens demographic breakdown → Windsor (name field, size field). */
    public static final Map<String, List<String>> AUDIENCE_FIELDS = Map.of(
            "age", List.of("audience_age_name", "audience_age_size"),
            "city", List.of("city", "audience_city_size"),
            "country", List.of("audience_country_name", "audience_country_size"),
            "gender", List.of("audience_gender_name", "audience_gender_size"));

    /** A media row: the post plus the snapshot metrics Windsor returned with it. */
    public record MediaRow(IgMedia media, Map<String, Long> metrics, Map<String, Long> extra) {
    }

    private WindsorMapper() {
    }

    public static boolean belongsTo(JsonNode row, String igUserId) {
        String id = text(row, "account_id");
        return id == null || id.equals(igUserId);
    }

    public static IgProfile profile(JsonNode row) {
        return new IgProfile(text(row, "account_id"), text(row, "username"), text(row, "name"), null, null,
                number(row, "followers_count"), number(row, "follows_count"), number(row, "media_count"));
    }

    public static MediaRow media(JsonNode row) {
        String type = upper(text(row, "media_type"));
        String product = upper(text(row, "media_product_type"));
        // Windsor reports Reels as media_type REEL; the Graph API (and PageLens) use VIDEO + product REELS.
        if ("REEL".equals(type) || "REELS".equals(type)) {
            type = "VIDEO";
            product = "REELS";
        }
        IgMedia m = new IgMedia(text(row, "media_id"), text(row, "media_caption"), type, product,
                text(row, "media_permalink"), text(row, "media_thumbnail_url"), text(row, "media_url"),
                instant(text(row, "timestamp")), number(row, "media_like_count"), number(row, "media_comments_count"),
                text(row, "media_shortcode"), row);
        Map<String, Long> v = new LinkedHashMap<>();
        put(v, "reach", number(row, "media_reach"));
        put(v, "views", number(row, "media_views"));
        put(v, "likes", m.likeCount());
        put(v, "comments", m.commentsCount());
        put(v, "shares", number(row, "media_shares"));
        put(v, "saved", number(row, "media_saved"));
        put(v, "follows", number(row, "media_follows"));
        put(v, "total_interactions", number(row, "media_engagement"));
        put(v, "profile_visits", number(row, "media_profile_visits"));
        put(v, "ig_reels_avg_watch_time", number(row, "media_reel_avg_watch_time"));
        Map<String, Long> extra = new LinkedHashMap<>();
        put(extra, "ig_reels_video_view_total_time", number(row, "media_reel_total_watch_time"));
        put(extra, "reels_skip_rate", number(row, "media_reel_skip_rate"));
        put(extra, "reposts", number(row, "media_reposts"));
        return new MediaRow(m, v, extra);
    }

    public static MediaRow story(JsonNode row) {
        IgMedia m = new IgMedia(text(row, "story_id"), null, "IMAGE", "STORY", text(row, "story_permalink"),
                text(row, "story_thumbnail_url"), null, instant(text(row, "story_timestamp")), null, null, null, row);
        Map<String, Long> v = new LinkedHashMap<>();
        put(v, "reach", number(row, "story_reach"));
        put(v, "views", number(row, "story_views"));
        put(v, "shares", number(row, "story_shares"));
        put(v, "total_interactions", number(row, "story_interactions"));
        Map<String, Long> extra = new LinkedHashMap<>();
        put(extra, "replies", number(row, "story_replies"));
        put(extra, "exits", number(row, "story_exits"));
        put(extra, "taps_forward", number(row, "story_taps_forward"));
        put(extra, "taps_back", number(row, "story_taps_back"));
        put(extra, "swipe_forward", number(row, "story_swipe_forward"));
        return new MediaRow(m, v, extra);
    }

    /** Groups comment rows into top-level threads with their replies, per Instagram media id. */
    public static Map<String, List<IgComment>> commentThreads(List<JsonNode> rows) {
        Map<String, Map<String, JsonNode>> byMedia = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            String media = text(r, "media_id");
            String id = text(r, "comment_id");
            if (media != null && id != null) {
                byMedia.computeIfAbsent(media, k -> new LinkedHashMap<>()).putIfAbsent(id, r);
            }
        }
        Map<String, List<IgComment>> out = new LinkedHashMap<>();
        byMedia.forEach((media, comments) -> {
            Map<String, List<IgComment>> replies = new LinkedHashMap<>();
            List<JsonNode> tops = new ArrayList<>();
            for (JsonNode r : comments.values()) {
                String parent = text(r, "comment_parent_id");
                if (parent != null && !parent.equals(text(r, "comment_id")) && comments.containsKey(parent)) {
                    replies.computeIfAbsent(parent, k -> new ArrayList<>()).add(comment(r, List.of()));
                } else {
                    tops.add(r);
                }
            }
            out.put(media, tops.stream()
                    .map(r -> comment(r, replies.getOrDefault(text(r, "comment_id"), List.of()))).toList());
        });
        return out;
    }

    private static IgComment comment(JsonNode r, List<IgComment> replies) {
        return new IgComment(text(r, "comment_id"), text(r, "comment_text"), instant(text(r, "comment_timestamp")),
                text(r, "comment_username"), number(r, "comment_like_count"), null, replies);
    }

    /** Sums one demographic breakdown's rows into key → followers. */
    public static Map<String, Long> audience(List<JsonNode> rows, String nameField, String sizeField) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            String key = text(r, nameField);
            Long size = number(r, sizeField);
            if (key != null && size != null) {
                out.merge(key, size, Long::sum);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ values

    public static String text(JsonNode row, String field) {
        JsonNode v = row.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Whole numbers; decimals are rounded. Null when missing or unreadable (never zero). */
    public static Long number(JsonNode row, String field) {
        JsonNode v = row.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return Math.round(v.asDouble());
        }
        String s = v.asString().trim().replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static LocalDate date(JsonNode row, String field) {
        String s = text(row, field);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static final List<DateTimeFormatter> OFFSET_FORMATS = List.of(DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"));
    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    /** Timestamps in the shapes Windsor and Meta use; no offset means UTC. Null when unreadable. */
    public static Instant instant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        for (DateTimeFormatter f : OFFSET_FORMATS) {
            try {
                return OffsetDateTime.parse(t, f).toInstant();
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        for (DateTimeFormatter f : LOCAL_FORMATS) {
            try {
                return LocalDateTime.parse(t, f).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        try {
            return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static void put(Map<String, Long> m, String key, Long value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
