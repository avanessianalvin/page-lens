package com.pagelens.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Windsor.ai connector API ({@code pagelens.windsor.*}), the read-only data source used when the Meta App
 * Dashboard can't be reached (docs/META_ACCESS.md).
 *
 * @param apiKey         Windsor API key; empty = Windsor sync is off. Secret: never logged.
 * @param baseUrl        connector API root (tests point it at a fake server)
 * @param connector      Windsor connector id for organic Instagram data
 * @param historyDays    how far back to ask for posts and daily metrics; the free plan only serves 30 days
 * @param requestTimeout Windsor fetches from Meta on demand, so calls can take a while
 */
@ConfigurationProperties(prefix = "pagelens.windsor")
public record WindsorProperties(String apiKey, String baseUrl, String connector, int historyDays,
                                Duration requestTimeout) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
