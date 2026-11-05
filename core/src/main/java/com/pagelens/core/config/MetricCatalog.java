package com.pagelens.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.pagelens.core.instagram.LoginType;

/**
 * Instagram metric names, bound from {@code instagram-metrics.yml}: the single place metric names live.
 *
 * @param mediaMetrics              per media product type (FEED, REELS, STORY)
 * @param facebookLoginMediaMetrics extra per-type metrics only valid with Facebook Login
 * @param accountDailyMetrics       account insights fetched per day
 * @param demographics              audience breakdown settings
 */
@ConfigurationProperties(prefix = "pagelens.instagram")
public record MetricCatalog(Map<String, List<String>> mediaMetrics,
                            Map<String, List<String>> facebookLoginMediaMetrics,
                            List<String> accountDailyMetrics,
                            Demographics demographics) {

    public record Demographics(String metric, String timeframe, List<String> breakdowns) {
    }

    /** Metrics to request for one media item. Unknown product types fall back to FEED. */
    public List<String> mediaMetricsFor(String productType, LoginType loginType) {
        String type = mediaMetrics.containsKey(productType) ? productType : "FEED";
        List<String> metrics = new ArrayList<>(mediaMetrics.get(type));
        if (loginType == LoginType.FACEBOOK_LOGIN && facebookLoginMediaMetrics != null) {
            metrics.addAll(facebookLoginMediaMetrics.getOrDefault(type, List.of()));
        }
        return metrics;
    }
}
