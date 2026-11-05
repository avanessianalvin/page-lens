package com.pagelens.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class PageLensPropertiesTest {

    @Test
    void blankTimeZoneFallsBackToSystemDefault() {
        assertThat(new PageLensProperties("logs", "", false, null).zoneId()).isEqualTo(ZoneId.systemDefault());
        assertThat(new PageLensProperties("logs", null, false, null).zoneId()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void explicitTimeZoneIsUsed() {
        assertThat(new PageLensProperties("logs", "Asia/Yerevan", false, null).zoneId()).isEqualTo(ZoneId.of("Asia/Yerevan"));
    }
}
