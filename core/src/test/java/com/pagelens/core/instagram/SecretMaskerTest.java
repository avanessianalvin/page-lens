package com.pagelens.core.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class SecretMaskerTest {

    @Test
    void masksEverySecretParameter() {
        String url = "https://graph.instagram.com/v27.0/me?fields=id&access_token=IGQV.abc-123&x=1"
                + "&client_secret=s3cr3t&fb_exchange_token=EAAB&code=AQD#_&input_token=zzz";
        String masked = SecretMasker.mask(url);

        assertThat(masked).contains("access_token=***", "client_secret=***", "fb_exchange_token=***", "code=***",
                "input_token=***", "fields=id", "x=1");
        assertThat(masked).doesNotContain("IGQV", "s3cr3t", "EAAB", "AQD", "zzz");
    }

    @Test
    void leavesLookalikeParametersAlone() {
        assertThat(SecretMasker.mask("error_code=190&metric=reach")).isEqualTo("error_code=190&metric=reach");
        assertThat(SecretMasker.mask(null)).isNull();
    }

    @Test
    void parsesBusinessUseCaseUsageHeader() {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        String header = """
                {"17841400000000001":[{"type":"instagram","call_count":42,"total_cputime":10,"total_time":61,
                "estimated_time_to_regain_access":0}]}""";
        ApiUsage usage = ApiUsage.parse(JsonMapper.builder().build(), header, null, now).orElseThrow();

        assertThat(usage.percent()).isEqualTo(61);
        assertThat(usage.regainAt()).isNull();
        assertThat(usage.rawHeader()).isEqualTo(header);
    }

    @Test
    void parsesRegainTimeAndAppUsageFallback() {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");
        JsonMapper json = JsonMapper.builder().build();
        String buc = "{\"1\":[{\"type\":\"instagram\",\"call_count\":100,\"estimated_time_to_regain_access\":12}]}";
        assertThat(ApiUsage.parse(json, buc, null, now).orElseThrow().regainAt()).isEqualTo(now.plusSeconds(720));

        String app = "{\"call_count\":5,\"total_time\":80,\"total_cputime\":3}";
        assertThat(ApiUsage.parse(json, null, app, now).orElseThrow().percent()).isEqualTo(80);
        assertThat(ApiUsage.parse(json, null, null, now)).isEmpty();
    }
}
