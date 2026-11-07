package com.pagelens.core.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Test
    void readsQuotesDoubledQuotesAndLineBreaksInsideQuotes() {
        String csv = "﻿a,b,c\r\n1,\"x, y\",\"say \"\"hi\"\"\nsecond line\"\r\n\r\n2,,last\n";
        List<List<String>> rows = CsvReader.read(csv.getBytes(StandardCharsets.UTF_8));
        assertThat(rows).containsExactly(
                List.of("a", "b", "c"),
                List.of("1", "x, y", "say \"hi\"\nsecond line"),
                List.of("2", "", "last"));
    }

    @Test
    void detectsSemicolonAndTabSeparatorsAndUtf16() {
        assertThat(CsvReader.read("a;b\n1;2,5".getBytes(StandardCharsets.UTF_8)))
                .containsExactly(List.of("a", "b"), List.of("1", "2,5"));
        byte[] utf16 = ("﻿a\tb\n1\t2").getBytes(StandardCharsets.UTF_16LE);
        assertThat(CsvReader.read(utf16)).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void importerHelpersReadExportValues() {
        assertThat(BusinessSuiteImporter.number("1,234")).isEqualTo(1234L);
        assertThat(BusinessSuiteImporter.number("")).isNull();
        assertThat(BusinessSuiteImporter.number("Lifetime")).isNull();
        assertThat(BusinessSuiteImporter.type("IG reel")).containsExactly("VIDEO", "REELS");
        assertThat(BusinessSuiteImporter.type("IG carousel")).containsExactly("CAROUSEL_ALBUM", "FEED");
        assertThat(BusinessSuiteImporter.type("IG story")).containsExactly("IMAGE", "STORY");
        assertThat(BusinessSuiteImporter.type("IG image")).containsExactly("IMAGE", "FEED");
        assertThat(BusinessSuiteImporter.type(null)).containsExactly("UNKNOWN", "FEED");
        ZoneId yerevan = ZoneId.of("Asia/Yerevan");
        assertThat(BusinessSuiteImporter.time("11/01/2026 12:00", yerevan)).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
        assertThat(BusinessSuiteImporter.time("2026-11-01 12:00", yerevan)).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
        assertThat(BusinessSuiteImporter.time("2026-11-01T08:00:00+0000", yerevan)).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
        assertThat(BusinessSuiteImporter.norm("﻿  Post   ID ")).isEqualTo("post id");
    }
}
