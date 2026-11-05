package com.pagelens.core.instagram;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.pagelens.core.config.MetaProperties;
import com.pagelens.core.instagram.IgModels.IssuedToken;
import com.pagelens.core.instagram.IgModels.LinkedIgAccount;

import tools.jackson.databind.JsonNode;

/**
 * OAuth exchanges for both login setups (endpoints in docs/API_NOTES.md). Every call that carries the app
 * secret runs server-side only.
 */
@Component
public class MetaOAuthClient {

    /** Read-only scopes for Phase 1. Publish scopes are added in Phase 3. */
    public static final List<String> INSTAGRAM_SCOPES = List.of(
            "instagram_business_basic", "instagram_business_manage_insights", "instagram_business_manage_comments");
    public static final List<String> FACEBOOK_SCOPES = List.of(
            "instagram_basic", "instagram_manage_insights", "instagram_manage_comments",
            "pages_show_list", "pages_read_engagement");

    /** Meta's long-lived tokens last ~60 days; used when a response omits expires_in. */
    private static final Duration DEFAULT_LONG_LIVED = Duration.ofDays(60);

    private final GraphApiClient client;
    private final MetaProperties meta;
    private final Clock clock;

    public MetaOAuthClient(GraphApiClient client, MetaProperties meta, Clock clock) {
        this.client = client;
        this.meta = meta;
        this.clock = clock;
    }

    public String authorizationUrl(LoginType type, String state) {
        Map<String, String> q = new LinkedHashMap<>();
        String base;
        if (type == LoginType.INSTAGRAM_LOGIN) {
            requireConfigured(meta.instagramLoginConfigured(), "META_INSTAGRAM_APP_ID / META_INSTAGRAM_APP_SECRET");
            base = meta.endpoints().instagramAuthorize();
            q.put("client_id", meta.instagramAppId());
            q.put("redirect_uri", meta.redirectUri());
            q.put("response_type", "code");
            q.put("scope", String.join(",", INSTAGRAM_SCOPES));
            q.put("state", state);
        } else {
            requireConfigured(meta.facebookLoginConfigured(), "META_APP_ID / META_APP_SECRET");
            base = meta.endpoints().facebookDialog() + "/" + meta.apiVersion() + "/dialog/oauth";
            q.put("client_id", meta.appId());
            q.put("redirect_uri", meta.redirectUri());
            q.put("response_type", "code");
            q.put("scope", String.join(",", FACEBOOK_SCOPES));
            q.put("state", state);
        }
        return base + "?" + q.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    // ---------------------------------------------------------------- Instagram Login

    /** Authorization code → short-lived token → long-lived (60-day) token. */
    public IssuedToken instagramExchangeCode(String code) {
        requireConfigured(meta.instagramLoginConfigured(), "META_INSTAGRAM_APP_ID / META_INSTAGRAM_APP_SECRET");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", meta.instagramAppId());
        form.put("client_secret", meta.instagramAppSecret());
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", meta.redirectUri());
        form.put("code", cleanCode(code));
        JsonNode body = client.postForm(null, meta.endpoints().instagramApi() + "/oauth/access_token", form);
        // Documented as {"data":[{access_token,user_id,permissions}]}; accept the unwrapped form too.
        JsonNode t = body.path("data").isArray() ? body.path("data").path(0) : body;
        String shortToken = t.path("access_token").asString(null);
        if (shortToken == null) {
            throw new IllegalStateException("Instagram code exchange returned no access_token");
        }
        String userId = t.path("user_id").isMissingNode() ? null : t.path("user_id").asString();
        String permissions = t.path("permissions").isArray()
                ? joinArray(t.path("permissions")) : t.path("permissions").asString(null);

        IssuedToken longLived = instagramLongLived(shortToken);
        return new IssuedToken(longLived.accessToken(), longLived.tokenType(), longLived.expiresAt(), userId, permissions);
    }

    public IssuedToken instagramLongLived(String shortLivedToken) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("grant_type", "ig_exchange_token");
        q.put("client_secret", meta.instagramAppSecret());
        q.put("access_token", shortLivedToken);
        return token(client.get(null, meta.endpoints().graphInstagram() + "/access_token", q), null);
    }

    /** Refreshes a long-lived Instagram Login token (it must be ≥ 24 h old and not yet expired). */
    public IssuedToken instagramRefresh(Long accountId, String longLivedToken) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("grant_type", "ig_refresh_token");
        q.put("access_token", longLivedToken);
        return token(client.get(accountId, meta.endpoints().graphInstagram() + "/refresh_access_token", q), null);
    }

    // ---------------------------------------------------------------- Facebook Login

    /** Authorization code → user token → long-lived user token. */
    public IssuedToken facebookExchangeCode(String code) {
        requireConfigured(meta.facebookLoginConfigured(), "META_APP_ID / META_APP_SECRET");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", meta.appId());
        q.put("redirect_uri", meta.redirectUri());
        q.put("client_secret", meta.appSecret());
        q.put("code", cleanCode(code));
        IssuedToken shortLived = token(client.get(null, client.graphBase(LoginType.FACEBOOK_LOGIN) + "/oauth/access_token", q),
                Duration.ofHours(1));
        return facebookLongLived(shortLived.accessToken());
    }

    /** Exchanges any Facebook user token (short- or long-lived) for a long-lived one. */
    public IssuedToken facebookLongLived(String userToken) {
        requireConfigured(meta.facebookLoginConfigured(), "META_APP_ID / META_APP_SECRET");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("grant_type", "fb_exchange_token");
        q.put("client_id", meta.appId());
        q.put("client_secret", meta.appSecret());
        q.put("fb_exchange_token", userToken);
        return token(client.get(null, client.graphBase(LoginType.FACEBOOK_LOGIN) + "/oauth/access_token", q),
                DEFAULT_LONG_LIVED);
    }

    /** IG professional accounts linked to the Pages this Facebook user can manage. */
    public List<LinkedIgAccount> facebookLinkedAccounts(String userToken) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("fields", "id,name,instagram_business_account{id,username}");
        q.put("limit", "100");
        q.put("access_token", userToken);
        JsonNode page = client.get(null, client.graphBase(LoginType.FACEBOOK_LOGIN) + "/me/accounts", q);
        List<LinkedIgAccount> out = new ArrayList<>();
        for (int pages = 0; pages < 10; pages++) {
            JsonNode data = page.path("data");
            for (int i = 0; i < data.size(); i++) {
                JsonNode p = data.get(i);
                JsonNode ig = p.path("instagram_business_account");
                if (ig.has("id")) {
                    out.add(new LinkedIgAccount(p.path("id").asString(), p.path("name").asString(null),
                            ig.path("id").asString(), ig.path("username").asString(null)));
                }
            }
            String next = page.path("paging").path("next").asString(null);
            if (next == null || data.isEmpty()) {
                break;
            }
            page = client.getAbsolute(null, next);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private IssuedToken token(JsonNode body, Duration fallbackLifetime) {
        String accessToken = body.path("access_token").asString(null);
        if (accessToken == null) {
            throw new IllegalStateException("Token endpoint returned no access_token");
        }
        long expiresIn = body.path("expires_in").asLong(0);
        Instant expiresAt = expiresIn > 0 ? clock.instant().plusSeconds(expiresIn)
                : fallbackLifetime == null ? null : clock.instant().plus(fallbackLifetime);
        return new IssuedToken(accessToken, body.path("token_type").asString("bearer"), expiresAt, null, null);
    }

    /** Instagram appends {@code #_} to the code in the redirect; it is not part of the code. */
    static String cleanCode(String code) {
        String c = code == null ? "" : code.trim();
        return c.endsWith("#_") ? c.substring(0, c.length() - 2) : c;
    }

    private static String joinArray(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            parts.add(arr.get(i).asString());
        }
        return String.join(",", parts);
    }

    private static void requireConfigured(boolean configured, String what) {
        if (!configured) {
            throw new IllegalStateException(what + " not set in .env; see docs/SETUP.md");
        }
    }
}
