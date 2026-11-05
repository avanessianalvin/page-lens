package com.pagelens.core.analysis;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Caption analysis inputs (plan section 2): length, script/language, hashtags, call to action.
 * Language is detected by Unicode script, which is reliable for this account mix (Farsi, Armenian, Latin) and needs
 * no model. Arabic script is labelled Farsi because that is what this owner writes; Arabic would land there too.
 */
public final class CaptionFeatures {

    private static final Pattern HASHTAG = Pattern.compile("(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_]+");

    /**
     * Call-to-action phrases (lower case) in English, Farsi and Armenian. A caption "has a CTA" if any appears.
     * Kept short and explicit on purpose; extend here, not in callers.
     */
    static final List<String> CTA_PHRASES = List.of(
            // English
            "link in bio", "comment", "share", "save this", "save for later", "tag a friend", "tag someone",
            "follow", "dm me", "dm us", "send this", "click", "sign up", "shop now", "book now", "swipe",
            // Farsi
            "لینک در بیو", "لینک بیو", "کامنت", "نظرتون", "نظرت", "ذخیره", "سیو", "اشتراک", "دنبال کن", "فالو",
            "تگ کن", "دایرکت", "پیام بدید",
            // Armenian
            "հղումը", "մեկնաբանեք", "կիսվեք", "պահպանեք", "հետևեք", "գրեք մեզ", "նշեք ընկերոջը");

    private CaptionFeatures() {
    }

    public record Features(int length, String lengthBucket, String script, int hashtags, String hashtagBucket,
                           boolean hasCta) {
    }

    public static Features of(String caption) {
        String c = caption == null ? "" : caption.strip();
        int length = c.codePointCount(0, c.length());
        int tags = 0;
        Matcher m = HASHTAG.matcher(c);
        while (m.find()) {
            tags++;
        }
        String lower = c.toLowerCase(Locale.ROOT);
        boolean cta = CTA_PHRASES.stream().anyMatch(lower::contains);
        return new Features(length, lengthBucket(length), script(c), tags, hashtagBucket(tags), cta);
    }

    static String lengthBucket(int length) {
        if (length == 0) {
            return "none";
        }
        if (length <= 50) {
            return "1-50";
        }
        if (length <= 150) {
            return "51-150";
        }
        if (length <= 300) {
            return "151-300";
        }
        return "300+";
    }

    static String hashtagBucket(int n) {
        if (n == 0) {
            return "0";
        }
        if (n <= 3) {
            return "1-3";
        }
        return n <= 10 ? "4-10" : "11+";
    }

    /** Dominant script among letters: Farsi (Arabic script), Armenian, Latin, mixed, or none (no letters). */
    static String script(String text) {
        int arabic = 0;
        int armenian = 0;
        int latin = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            switch (s) {
                case ARABIC -> arabic++;
                case ARMENIAN -> armenian++;
                case LATIN -> latin++;
                default -> other++;
            }
        }
        int total = arabic + armenian + latin + other;
        if (total == 0) {
            return "none";
        }
        if (arabic * 10 >= total * 7) {
            return "farsi";
        }
        if (armenian * 10 >= total * 7) {
            return "armenian";
        }
        if (latin * 10 >= total * 7) {
            return "latin";
        }
        return "mixed";
    }
}
