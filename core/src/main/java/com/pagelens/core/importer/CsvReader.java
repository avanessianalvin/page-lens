package com.pagelens.core.importer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 reader for spreadsheet exports: quoted fields, doubled quotes, line breaks inside quotes,
 * CRLF or LF. Handles a UTF-8 or UTF-16 byte-order mark and picks the delimiter (comma, semicolon or tab) from
 * the header line, because Excel re-saves CSVs with the locale's separator.
 */
public final class CsvReader {

    private CsvReader() {
    }

    public static List<List<String>> read(byte[] bytes) {
        return parse(decode(bytes));
    }

    static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        String s = new String(b, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    static char delimiter(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        char best = ',';
        long bestCount = header.chars().filter(c -> c == ',').count();
        for (char c : new char[] {';', '\t'}) {
            long n = header.chars().filter(x -> x == c).count();
            if (n > bestCount) {
                best = c;
                bestCount = n;
            }
        }
        return best;
    }

    static List<List<String>> parse(String text) {
        char sep = delimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"' && cell.isEmpty()) {
                quoted = true;
                any = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || !cell.isEmpty()) {
                    row.add(cell.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || !cell.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
