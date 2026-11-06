package com.pagelens.core.export;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pagelens.core.analysis.AnalysisModels.PostStat;
import com.pagelens.core.analysis.AnalysisModels.Range;
import com.pagelens.core.analysis.AnalysisService;
import com.pagelens.core.repo.DailyMetricRepository;
import com.pagelens.core.repo.DailyMetricRepository.DailyMetric;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.repo.MediaRepository.Post;

/**
 * Data export (plan section 8: posts.csv / posts.json, plus daily metrics). CSV is RFC 4180 with a UTF-8 BOM so
 * Excel opens Farsi and Armenian text correctly, and cells that start like a formula are prefixed with an
 * apostrophe so a caption can never execute in a spreadsheet. Empty cells mean "not returned by Instagram".
 */
@Service
public class ExportService {

    static final List<String> POST_COLUMNS = List.of("post_id", "instagram_id", "posted_at", "format", "media_type",
            "product_type", "permalink", "caption", "reach", "views", "likes", "comments", "shares", "saves", "follows",
            "interactions", "engagement_rate", "insights_as_of");

    static final List<String> DAILY_COLUMNS = List.of("day_utc", "followers", "follower_change", "reach", "views",
            "accounts_engaged", "total_interactions", "likes", "comments", "shares", "saves", "replies",
            "profile_links_taps");

    private final AnalysisService analysis;
    private final MediaRepository media;
    private final DailyMetricRepository daily;

    public ExportService(AnalysisService analysis, MediaRepository media, DailyMetricRepository daily) {
        this.analysis = analysis;
        this.media = media;
        this.daily = daily;
    }

    /** One map per post (stable column order), newest first. */
    public List<Map<String, Object>> posts(long accountId, Range range) {
        ZoneId zone = analysis.zone();
        List<Post> posts = media.postsInRange(accountId, range.from(), range.to());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = posts.size() - 1; i >= 0; i--) {
            Post p = posts.get(i);
            PostStat s = com.pagelens.core.analysis.Analytics.stat(p);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("post_id", p.id());
            r.put("instagram_id", p.igMediaId());
            r.put("posted_at", time(p.postedAt(), zone));
            r.put("format", s.format().name());
            r.put("media_type", p.mediaType());
            r.put("product_type", p.productType());
            r.put("permalink", p.permalink());
            r.put("caption", p.caption());
            r.put("reach", s.reach());
            r.put("views", s.views());
            r.put("likes", s.likes());
            r.put("comments", s.comments());
            r.put("shares", s.shares());
            r.put("saves", s.saves());
            r.put("follows", s.follows());
            r.put("interactions", s.interactions());
            r.put("engagement_rate", s.engagementRate());
            r.put("insights_as_of", p.latest() == null ? null : time(p.latest().capturedAt(), zone));
            rows.add(r);
        }
        return rows;
    }

    public String postsCsv(long accountId, Range range) {
        return csv(POST_COLUMNS, posts(accountId, range));
    }

    public String dailyCsv(long accountId, Range range) {
        LocalDate from = range.from().isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                ? LocalDate.of(2000, 1, 1) : LocalDate.ofInstant(range.from(), ZoneOffset.UTC);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DailyMetric d : daily.range(accountId, from, LocalDate.ofInstant(range.to(), ZoneOffset.UTC))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day_utc", d.day());
            r.put("followers", d.followersCount());
            r.put("follower_change", d.followerDelta());
            r.put("reach", d.reach());
            r.put("views", d.views());
            r.put("accounts_engaged", d.accountsEngaged());
            r.put("total_interactions", d.totalInteractions());
            r.put("likes", d.likes());
            r.put("comments", d.comments());
            r.put("shares", d.shares());
            r.put("saves", d.saves());
            r.put("replies", d.replies());
            r.put("profile_links_taps", d.profileLinksTaps());
            rows.add(r);
        }
        return csv(DAILY_COLUMNS, rows);
    }

    static String csv(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("﻿"); // UTF-8 BOM: Excel otherwise mangles non-Latin text
        sb.append(String.join(",", columns)).append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cell(row.get(columns.get(i))));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!(value instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s; // neutralise spreadsheet formula injection
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String time(Instant i, ZoneId zone) {
        return i == null ? null : i.atZone(zone).toOffsetDateTime().toString();
    }
}
