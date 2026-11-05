package com.pagelens.mcp;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pagelens.core.analysis.AnalysisModels.PostStat;
import com.pagelens.core.repo.InsightRepository.Snapshot;
import com.pagelens.core.repo.MediaRepository.Post;

import tools.jackson.databind.json.JsonMapper;

/**
 * Compact JSON shapes for tool results. Tool output lands in Claude's context, so: no nulls (absent means
 * "Instagram didn't return it"), timestamps in the owner's zone, captions trimmed, and no internal noise.
 */
final class McpViews {

    static final int CAPTION_PREVIEW = 160;

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private final ZoneId zone;

    McpViews(ZoneId zone) {
        this.zone = zone;
    }

    String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    /** ISO-8601 with the owner's offset, e.g. 2026-11-04T19:30:00+04:00. */
    String time(Instant i) {
        return i == null ? null : i.atZone(zone).toOffsetDateTime().toString();
    }

    static String preview(String caption) {
        if (caption == null || caption.isBlank()) {
            return null;
        }
        String c = caption.strip().replaceAll("\\s+", " ");
        return c.codePointCount(0, c.length()) <= CAPTION_PREVIEW ? c
                : c.substring(0, c.offsetByCodePoints(0, CAPTION_PREVIEW)) + "…";
    }

    Map<String, Object> post(PostStat p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("postId", p.postId());
        m.put("format", p.format());
        m.put("postedAt", time(p.postedAt()));
        m.put("caption", preview(p.caption()));
        m.put("permalink", p.permalink());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("reach", p.reach());
        metrics.put("views", p.views());
        metrics.put("likes", p.likes());
        metrics.put("comments", p.comments());
        metrics.put("shares", p.shares());
        metrics.put("saves", p.saves());
        metrics.put("follows", p.follows());
        metrics.put("interactions", p.interactions());
        metrics.put("engagementRate", p.engagementRate());
        m.put("metrics", metrics);
        return m;
    }

    List<Map<String, Object>> posts(List<PostStat> posts) {
        List<Map<String, Object>> out = new ArrayList<>();
        posts.forEach(p -> out.add(post(p)));
        return out;
    }

    Map<String, Object> snapshot(Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capturedAt", time(s.capturedAt()));
        m.put("reach", s.reach());
        m.put("views", s.views());
        m.put("likes", s.likes());
        m.put("comments", s.comments());
        m.put("shares", s.shares());
        m.put("saves", s.saves());
        m.put("follows", s.follows());
        m.put("totalInteractions", s.totalInteractions());
        m.put("profileVisits", s.profileVisits());
        m.put("avgWatchTimeMs", s.avgWatchTimeMs());
        return m;
    }

    /** Keeps the first, the last and evenly spaced points in between, so long histories stay small. */
    static <T> List<T> downsample(List<T> items, int max) {
        if (items.size() <= max) {
            return items;
        }
        List<T> out = new ArrayList<>();
        double step = (items.size() - 1) / (double) (max - 1);
        for (int i = 0; i < max; i++) {
            out.add(items.get((int) Math.round(i * step)));
        }
        return out;
    }

    Map<String, Object> postRow(Post p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("postId", p.id());
        m.put("type", p.productType());
        m.put("mediaType", p.mediaType());
        m.put("postedAt", time(p.postedAt()));
        m.put("caption", preview(p.caption()));
        m.put("permalink", p.permalink());
        m.put("likes", p.latest() != null && p.latest().likes() != null ? p.latest().likes() : p.likeCount());
        m.put("comments", p.latest() != null && p.latest().comments() != null ? p.latest().comments() : p.commentsCount());
        if (p.latest() != null) {
            m.put("reach", p.latest().reach());
            m.put("views", p.latest().views());
            m.put("shares", p.latest().shares());
            m.put("saves", p.latest().saves());
            m.put("follows", p.latest().follows());
            m.put("insightsAsOf", time(p.latest().capturedAt()));
        }
        return m;
    }
}
