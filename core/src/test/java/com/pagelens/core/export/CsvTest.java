package com.pagelens.core.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void quotesEscapesAndStartsWithBom() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", "plain");
        row.put("b", "has, comma");
        row.put("c", "say \"hi\"");
        row.put("d", "two\nlines");
        row.put("e", null);
        String csv = ExportService.csv(List.of("a", "b", "c", "d", "e"), List.of(row));

        assertThat(csv).startsWith("﻿a,b,c,d,e\r\n");
        assertThat(csv).contains("plain,\"has, comma\",\"say \"\"hi\"\"\",\"two\nlines\",\r\n");
    }

    @Test
    void neutralisesSpreadsheetFormulasButNotNumbers() {
        assertThat(ExportService.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ExportService.cell("+1 555")).isEqualTo("'+1 555");
        assertThat(ExportService.cell("@mention")).isEqualTo("'@mention");
        assertThat(ExportService.cell(-5L)).isEqualTo("-5");
        assertThat(ExportService.cell(0.1234)).isEqualTo("0.1234");
    }

    @Test
    void keepsFarsiAndArmenianIntact() {
        assertThat(ExportService.cell("سلام Բարեւ")).isEqualTo("سلام Բարեւ");
    }
}
