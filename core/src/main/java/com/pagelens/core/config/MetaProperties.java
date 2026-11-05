package com.pagelens.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Meta app credentials and API endpoints ({@code pagelens.meta.*}). Secrets come from env vars / .env.
 *
 * @param apiVersion         Graph API version segment, e.g. {@code v27.0}
 * @param appId              Facebook Login app id
 * @param appSecret          Facebook Login app secret
 * @param instagramAppId     Instagram Login app id ("Instagram app ID" in the App Dashboard)
 * @param instagramAppSecret Instagram Login app secret
 * @param redirectUri        OAuth redirect registered in the App Dashboard
 * @param endpoints          hosts, overridable for tests
 */
@ConfigurationProperties(prefix = "pagelens.meta")
public record MetaProperties(String apiVersion, String appId, String appSecret, String instagramAppId,
                             String instagramAppSecret, String redirectUri, Endpoints endpoints) {

    public record Endpoints(String graphInstagram, String graphFacebook, String instagramApi,
                            String instagramAuthorize, String facebookDialog, String rupload) {
    }

    public boolean instagramLoginConfigured() {
        return notBlank(instagramAppId) && notBlank(instagramAppSecret);
    }

    public boolean facebookLoginConfigured() {
        return notBlank(appId) && notBlank(appSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
