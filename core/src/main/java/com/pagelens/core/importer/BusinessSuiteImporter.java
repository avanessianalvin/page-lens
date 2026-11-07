package com.pagelens.core.importer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.pagelens.core.config.PageLensProperties;
import com.pagelens.core.instagram.GraphJson;
import com.pagelens.core.instagram.IgModels.IgMedia;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.InsightRepository;
import com.pagelens.core.repo.InsightRepository.Snapshot;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.windsor.WindsorMapper;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Imports Meta Business Suite's content export (Insights → Content → Export data, as CSV) into PageLens: one
 * post per row, plus one insight snapshot per post taken at import time. Columns are matched by header name, so
 * reordered or extra columns are fine; unknown numeric columns go to {@code extra_metrics}. Rows carrying an
 * "Account ID" create or reuse that account (new ones are {@code IMPORT} accounts); without that column the
 * caller picks the account. Posts are upserted on the Instagram post id, so re-importing never duplicates them.
 */
@Service
public class BusinessSuiteImporter {

    /** What an import did. {@code skipped} lists the first problem rows with reasons. */
    public record Report(int rows, int posts, int snapshots, List<String> accounts, List<String> mappedColumns,
                         List<String> ignoredColumns, List<String> skipped, List<String> warnings) {
    }

    /** Thrown when the file can't be imported at all (wrong file, no account). */
    public static class ImportException extends RuntimeException {
        public ImportException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(BusinessSuiteImporter.class);
    private static final int MAX_SKIPPED_LISTED = 20;

    private enum Col { POST_ID, ACCOUNT_ID, USERNAME, ACCOUNT_NAME, CAPTION, PUBLISHED, PERMALINK, POST_TYPE, DURATION }

    private static final Map<String, Col> COLUMNS = new LinkedHashMap<>();
    /** Header → snapshot metric key (the same keys the Graph sync uses). */
    private static final Map<String, String> METRICS = new LinkedHashMap<>();
    /** Text columns that carry no data PageLens uses ("Date" is "Lifetime" in these exports). */
    private static final Set<String> IGNORED = Set.of("date", "data comment");

    static {
        alias(Col.POST_ID, "post id", "media id", "ig media id");
        alias(Col.ACCOUNT_ID, "account id", "instagram account id", "ig user id");
        alias(Col.USERNAME, "account username", "username");
        alias(Col.ACCOUNT_NAME, "account name");
        alias(Col.CAPTION, "description", "caption");
        alias(Col.PUBLISHED, "publish time", "published", "publish date", "timestamp");
        alias(Col.PERMALINK, "permalink", "post link", "link");
        alias(Col.POST_TYPE, "post type", "media type");
        alias(Col.DURATION, "duration (sec)", "duration (seconds)", "duration");
        metric("reach", "reach", "accounts reached");
        metric("views", "views", "plays");
        metric("likes", "likes");
        metric("comments", "comments");
        metric("shares", "shares");
        metric("saved", "saves", "saved");
        metric("follows", "follows");
        metric("total_interactions", "interactions", "total interactions", "engagement");
        metric("profile_visits", "profile visits");
        metric("ig_reels_avg_watch_time", "average watch time (ms)");
    }

    private static void alias(Col c, String... names) {
        for (String n : names) {
            COLUMNS.put(n, c);
        }
    }

    private static void metric(String key, String... names) {
        for (String n : names) {
            METRICS.put(n, key);
        }
    }

    private static final List<DateTimeFormatter> LOCAL_TIMES = List.of(
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("M/d/yyyy h:mm a", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss", Locale.ROOT),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    private final AccountRepository accounts;
    private final MediaRepository media;
    private final InsightRepository insights;
    private final AuditLogRepository audit;
    private final PageLensProperties props;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public BusinessSuiteImporter(AccountRepository accounts, MediaRepository media, InsightRepository insights,
                                 AuditLogRepository audit, PageLensProperties props, TransactionTemplate tx,
                                 GraphJson json, Clock clock) {
        this.accounts = accounts;
        this.media = media;
        this.insights = insights;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
        this.json = json.mapper();
        this.clock = clock;
    }

    /**
     * @param fallbackAccountId account for rows without an "Account ID" column; null to require the column
     */
    public Report importCsv(byte[] csv, Long fallbackAccountId, Actor actor) {
        List<List<String>> table = CsvReader.read(csv);
        if (table.isEmpty()) {
            throw new ImportException("The file is empty");
        }
        List<String> header = table.getFirst().stream().map(BusinessSuiteImporter::norm).toList();
        Map<Col, Integer> cols = new LinkedHashMap<>();
        Map<Integer, String> metricCols = new LinkedHashMap<>();
        Map<Integer, String> extraCols = new LinkedHashMap<>();
        List<String> mapped = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            if (h.isEmpty()) {
                continue;
            }
            if (COLUMNS.containsKey(h)) {
                cols.putIfAbsent(COLUMNS.get(h), i);
                mapped.add(table.getFirst().get(i).trim());
            } else if (METRICS.containsKey(h)) {
                metricCols.put(i, METRICS.get(h));
                mapped.add(table.getFirst().get(i).trim());
            } else if (IGNORED.contains(h)) {
                ignored.add(table.getFirst().get(i).trim());
            } else {
                extraCols.put(i, h.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", ""));
            }
        }
        if (!cols.containsKey(Col.POST_ID)) {
            throw new ImportException("This doesn't look like a Meta Business Suite content export: there is no "
                    + "\"Post ID\" column. Columns found: " + String.join(", ", table.getFirst()));
        }
        Account fallback = null;
        if (!cols.containsKey(Col.ACCOUNT_ID)) {
            if (fallbackAccountId == null) {
                throw new ImportException("The file has no \"Account ID\" column; choose the account to import into");
            }
            fallback = accounts.findById(fallbackAccountId)
                    .orElseThrow(() -> new ImportException("Unknown account " + fallbackAccountId));
        }
        Instant capturedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        ZoneId zone = props.zoneId();
        Account fixed = fallback;

        return tx.execute(status -> {
            int posts = 0;
            int snapshots = 0;
            int skippedCount = 0;
            List<String> skipped = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            Set<String> touched = new LinkedHashSet<>();
            Map<String, Long> accountIds = new LinkedHashMap<>();
            Set<String> extraNonNumeric = new LinkedHashSet<>();
            int badTimes = 0;

            for (int r = 1; r < table.size(); r++) {
                List<String> row = table.get(r);
                if (row.stream().allMatch(String::isBlank)) {
                    continue;
                }
                String postId = cell(row, cols.get(Col.POST_ID));
                if (postId == null) {
                    skippedCount++;
                    note(skipped, "row " + (r + 1) + ": no Post ID");
                    continue;
                }
                long accountId;
                String username;
                if (fixed != null) {
                    accountId = fixed.id();
                    username = fixed.username();
                } else {
                    String igUserId = cell(row, cols.get(Col.ACCOUNT_ID));
                    if (igUserId == null) {
                        skippedCount++;
                        note(skipped, "row " + (r + 1) + ": no Account ID");
                        continue;
                    }
                    String user = cell(row, cols.get(Col.USERNAME));
                    accountId = accountIds.computeIfAbsent(igUserId,
                            id -> accounts.upsertReadOnlySource(id, LoginType.IMPORT, user));
                    username = accounts.findById(accountId).map(Account::username).orElse(igUserId);
                }

                String published = cell(row, cols.get(Col.PUBLISHED));
                Instant postedAt = time(published, zone);
                if (published != null && postedAt == null) {
                    badTimes++;
                }
                String[] type = type(cell(row, cols.get(Col.POST_TYPE)));
                boolean story = "STORY".equals(type[1]);

                Map<String, Long> v = new LinkedHashMap<>();
                metricCols.forEach((i, key) -> {
                    Long n = number(cell(row, i));
                    if (n != null) {
                        v.put(key, n);
                    }
                });
                ObjectNode extra = json.createObjectNode();
                extraCols.forEach((i, key) -> {
                    String raw = cell(row, i);
                    Long n = number(raw);
                    if (n != null) {
                        extra.put(key, n);
                    } else if (raw != null) {
                        extraNonNumeric.add(table.getFirst().get(i).trim());
                    }
                });
                Long duration = number(cell(row, cols.get(Col.DURATION)));
                if (duration != null) {
                    extra.put("duration_sec", duration);
                }

                ObjectNode raw = json.createObjectNode();
                raw.put("source", "meta_business_suite_csv");
                for (int i = 0; i < Math.min(row.size(), header.size()); i++) {
                    if (!header.get(i).isEmpty() && i != index(cols, Col.CAPTION)) {
                        raw.put(table.getFirst().get(i).trim(), row.get(i));
                    }
                }
                IgMedia m = new IgMedia(postId, cell(row, cols.get(Col.CAPTION)), type[0], type[1],
                        cell(row, cols.get(Col.PERMALINK)), null, null, postedAt, v.get("likes"), v.get("comments"),
                        null, raw);
                long mediaId = media.upsert(accountId, m, story, raw.toString());
                posts++;
                touched.add("@" + username);
                if (!v.isEmpty() || !extra.isEmpty()) {
                    insights.insert(mediaId, new Snapshot(capturedAt, v.get("reach"), v.get("views"), v.get("likes"),
                            v.get("comments"), v.get("shares"), v.get("saved"), v.get("follows"),
                            v.get("total_interactions"), v.get("profile_visits"), v.get("ig_reels_avg_watch_time"),
                            extra.isEmpty() ? null : extra.toString()));
                    media.markInsightsFetched(mediaId, capturedAt);
                    snapshots++;
                }
            }
            if (badTimes > 0) {
                warnings.add(badTimes + " publish time(s) couldn't be read and were left empty");
            }
            if (!extraNonNumeric.isEmpty()) {
                warnings.add("Unrecognised text columns were kept only in the raw row: " + String.join(", ", extraNonNumeric));
            }
            if (skippedCount > skipped.size()) {
                skipped.add("… and " + (skippedCount - skipped.size()) + " more");
            }
            if (posts == 0) {
                warnings.add("No posts were imported");
            }
            Report report = new Report(table.size() - 1, posts, snapshots, List.copyOf(touched), mapped, ignored,
                    skipped, warnings);
            audit.record(actor, "csv_import", String.join(" ", touched),
                    posts + " post(s), " + snapshots + " snapshot(s), " + skippedCount + " skipped", false, "ok");
            log.info("Business Suite import: {} post(s), {} snapshot(s), {} skipped, accounts {}", posts, snapshots,
                    skippedCount, touched);
            return report;
        });
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static void note(List<String> skipped, String reason) {
        if (skipped.size() < MAX_SKIPPED_LISTED) {
            skipped.add(reason);
        }
    }

    private static int index(Map<Col, Integer> cols, Col c) {
        Integer i = cols.get(c);
        return i == null ? -1 : i;
    }

    static String norm(String header) {
        return header.replace("﻿", "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String cell(List<String> row, Integer i) {
        if (i == null || i >= row.size()) {
            return null;
        }
        String s = row.get(i).trim();
        return s.isEmpty() ? null : s;
    }

    /** Counts as exported: "1,234", "1234", "12.0". Empty or text is null (never zero). */
    static Long number(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace(",", "").replace(" ", "").replace(" ", "");
        if (t.isEmpty() || !t.matches("-?\\d+(\\.\\d+)?")) {
            return null;
        }
        return Math.round(Double.parseDouble(t));
    }

    /** Export times are in the owner's zone (Business Suite shows the viewer's local time). */
    static Instant time(String s, ZoneId zone) {
        if (s == null) {
            return null;
        }
        for (DateTimeFormatter f : LOCAL_TIMES) {
            try {
                return LocalDateTime.parse(s, f).atZone(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return WindsorMapper.instant(s);
    }

    /** {media_type, media_product_type} from Business Suite's "Post type" (IG image, IG reel, IG story…). */
    static String[] type(String postType) {
        String t = postType == null ? "" : postType.toLowerCase(Locale.ROOT);
        if (t.contains("story")) {
            return new String[] {t.contains("video") ? "VIDEO" : "IMAGE", "STORY"};
        }
        if (t.contains("reel")) {
            return new String[] {"VIDEO", "REELS"};
        }
        if (t.contains("carousel") || t.contains("album")) {
            return new String[] {"CAROUSEL_ALBUM", "FEED"};
        }
        if (t.contains("video")) {
            return new String[] {"VIDEO", "FEED"};
        }
        if (t.contains("image") || t.contains("photo")) {
            return new String[] {"IMAGE", "FEED"};
        }
        return new String[] {"UNKNOWN", "FEED"};
    }
}
