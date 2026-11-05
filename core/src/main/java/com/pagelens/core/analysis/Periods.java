package com.pagelens.core.analysis;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import com.pagelens.core.analysis.AnalysisModels.Range;

/** Resolves {@code period} / {@code from} / {@code to} inputs into a {@link Range} in the owner's time zone. */
public final class Periods {

    public static final List<String> NAMES = List.of("last_7_days", "last_30_days", "last_90_days", "last_365_days", "all");

    private Periods() {
    }

    /**
     * {@code from}/{@code to} are calendar days (YYYY-MM-DD, inclusive) in {@code zone} and win over {@code period}.
     * Without them, {@code period} counts back from now; null means {@code defaultPeriod}.
     */
    public static Range resolve(String period, String from, String to, String defaultPeriod, ZoneId zone, Clock clock) {
        Instant now = clock.instant();
        LocalDate fromDay = day(from, "from");
        LocalDate toDay = day(to, "to");
        if (fromDay != null || toDay != null) {
            Instant start = fromDay == null ? Instant.EPOCH : fromDay.atStartOfDay(zone).toInstant();
            Instant end = toDay == null ? now : toDay.plusDays(1).atStartOfDay(zone).toInstant();
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("from must be before to");
            }
            return new Range(start, end, (fromDay == null ? "start" : fromDay) + " to " + (toDay == null ? "now" : toDay));
        }
        String p = period == null || period.isBlank() ? defaultPeriod : period.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "last_7_days" -> new Range(now.minusSeconds(7L * 86400), now, p);
            case "last_30_days" -> new Range(now.minusSeconds(30L * 86400), now, p);
            case "last_90_days" -> new Range(now.minusSeconds(90L * 86400), now, p);
            case "last_365_days" -> new Range(now.minusSeconds(365L * 86400), now, p);
            case "all" -> new Range(Instant.EPOCH, now, p);
            default -> throw new IllegalArgumentException("Unknown period '" + period + "'. Use one of " + NAMES
                    + ", or from/to dates (YYYY-MM-DD)");
        };
    }

    private static LocalDate day(String s, String name) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date like 2026-11-05");
        }
    }
}
