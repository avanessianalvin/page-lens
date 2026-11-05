package com.pagelens.core.instagram;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pagelens.core.config.HttpProperties;
import com.pagelens.core.config.MetaProperties;
import com.pagelens.core.instagram.GraphApiException.Kind;
import com.pagelens.core.logging.CorrelationId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Low-level Graph API transport: builds URLs, sends requests through the {@link RateLimitGuard}, retries
 * transient failures with exponential backoff, classifies errors and records every attempt in api_call_log
 * and api-calls.log. <b>Secrets never reach a log line</b>: every URL passes through {@link SecretMasker}.
 */
@Component
public class GraphApiClient {

    private static final Logger apiLog = LoggerFactory.getLogger("pagelens.api-calls");
    private static final Logger log = LoggerFactory.getLogger(GraphApiClient.class);
    private static final Set<Integer> RATE_LIMIT_CODES = Set.of(4, 17, 32, 613, 80002);
    private static final Set<Integer> TOKEN_CODES = Set.of(190, 102, 463, 467);

    private final HttpClient http;
    private final HttpProperties props;
    private final MetaProperties meta;
    private final RateLimitGuard guard;
    private final ApiCallRecorder recorder;
    private final JsonMapper json;
    private final Clock clock;

    public GraphApiClient(HttpProperties props, MetaProperties meta, RateLimitGuard guard, ApiCallRecorder recorder,
                          GraphJson json, Clock clock) {
        this.props = props;
        this.meta = meta;
        this.guard = guard;
        this.recorder = recorder;
        this.json = json.mapper();
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** {@code https://graph.instagram.com/v27.0} or {@code https://graph.facebook.com/v27.0}. */
    public String graphBase(LoginType type) {
        String host = type == LoginType.INSTAGRAM_LOGIN ? meta.endpoints().graphInstagram() : meta.endpoints().graphFacebook();
        return host + "/" + meta.apiVersion();
    }

    /** GET {@code url} with query {@code params} (values are URL-encoded; may include access_token). */
    public JsonNode get(Long accountId, String url, Map<String, String> params) {
        String query = encode(params);
        String full = query.isEmpty() ? url : url + (url.contains("?") ? "&" : "?") + query;
        return send(accountId, "GET", full, null);
    }

    /** GET an absolute URL as returned in {@code paging.next} (already carries its parameters). */
    public JsonNode getAbsolute(Long accountId, String absoluteUrl) {
        return send(accountId, "GET", absoluteUrl, null);
    }

    /** POST an {@code application/x-www-form-urlencoded} body (OAuth code exchange). */
    public JsonNode postForm(Long accountId, String url, Map<String, String> form) {
        return send(accountId, "POST", url, encode(form));
    }

    /**
     * A write (reply, hide, create container, publish): form-encoded POST, sent <b>exactly once</b>. Writes are
     * never retried automatically, because a retry after a lost response could reply or publish twice. Callers
     * decide, usually after checking state on Instagram.
     */
    public JsonNode postWrite(Long accountId, String url, Map<String, String> form) {
        return sendOnce(accountId, new Call("POST", url, "application/x-www-form-urlencoded",
                HttpRequest.BodyPublishers.ofString(encode(form)), Map.of(), props.requestTimeout()));
    }

    /** DELETE with query parameters, sent exactly once (see {@link #postWrite}). */
    public JsonNode deleteWrite(Long accountId, String url, Map<String, String> params) {
        String query = encode(params);
        return sendOnce(accountId, new Call("DELETE", query.isEmpty() ? url : url + "?" + query, null,
                HttpRequest.BodyPublishers.noBody(), Map.of(), props.requestTimeout()));
    }

    /**
     * Raw binary upload (resumable video upload to rupload.facebook.com), starting at {@code offset}.
     * {@code headers} may carry the access token ({@code Authorization: OAuth ...}); headers are never logged.
     */
    public JsonNode uploadFile(Long accountId, String url, Map<String, String> headers, java.nio.file.Path file,
                               long offset) {
        HttpRequest.BodyPublisher body;
        try {
            body = offset == 0 ? HttpRequest.BodyPublishers.ofFile(file)
                    : HttpRequest.BodyPublishers.ofInputStream(() -> {
                        try {
                            var in = java.nio.file.Files.newInputStream(file);
                            in.skipNBytes(offset);
                            return in;
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        } catch (java.io.FileNotFoundException e) {
            throw new IllegalArgumentException("Upload file not found: " + file.getFileName());
        }
        return sendOnce(accountId, new Call("POST", url, "application/octet-stream", body, headers,
                Duration.ofMinutes(30)));
    }

    /** One HTTP request. {@code contentType} null = none. */
    private record Call(String method, String url, String contentType, HttpRequest.BodyPublisher body,
                        Map<String, String> headers, Duration timeout) {
    }

    private JsonNode send(Long accountId, String method, String url, String formBody) {
        Call call = formBody == null
                ? new Call("GET", url, null, HttpRequest.BodyPublishers.noBody(), Map.of(), props.requestTimeout())
                : new Call("POST", url, "application/x-www-form-urlencoded",
                        HttpRequest.BodyPublishers.ofString(formBody), Map.of(), props.requestTimeout());
        int attempts = Math.max(1, props.maxAttempts());
        Duration backoff = props.initialBackoff();
        for (int attempt = 1; ; attempt++) {
            try {
                return sendOnce(accountId, call);
            } catch (GraphApiException e) {
                if (e.kind() != Kind.TRANSIENT || attempt >= attempts) {
                    throw e;
                }
                log.warn("Transient Graph API failure (attempt {}/{}), retrying in {} ms: {}",
                        attempt, attempts, backoff.toMillis(), e.getMessage());
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    private JsonNode sendOnce(Long accountId, Call call) {
        String method = call.method();
        String masked = SecretMasker.mask(call.url());
        guard.acquire(accountId);
        long started = System.nanoTime();
        Integer status = null;
        ApiUsage usage = null;
        String errorCode = null;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(call.url()))
                    .timeout(call.timeout())
                    .header("Accept", "application/json")
                    .method(method, call.body());
            if (call.contentType() != null) {
                req.header("Content-Type", call.contentType());
            }
            call.headers().forEach(req::header);
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = resp.statusCode();
            usage = ApiUsage.parse(json,
                    resp.headers().firstValue("x-business-use-case-usage").orElse(null),
                    resp.headers().firstValue("x-app-usage").orElse(null),
                    clock.instant()).orElse(null);
            guard.observe(accountId, usage);

            JsonNode body = parse(resp.body());
            if (status >= 200 && status < 300 && !body.has("error")) {
                return body;
            }
            GraphApiException error = classify(status, body);
            errorCode = error.errorCode();
            if (error.kind() == Kind.RATE_LIMITED) {
                Instant until = error.retryAt() != null ? error.retryAt() : usage != null ? usage.regainAt() : null;
                guard.blockUntil(accountId, until);
                usage = new ApiUsage(Math.max(100, usage == null ? 0 : usage.percent()), until,
                        usage == null ? null : usage.rawHeader());
            }
            throw error;
        } catch (IOException e) {
            errorCode = "IO";
            throw new GraphApiException(Kind.TRANSIENT, 0, null, null,
                    "Network error calling " + masked + ": " + e.getClass().getSimpleName(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorCode = "INTERRUPTED";
            throw new GraphApiException(Kind.TRANSIENT, 0, null, null, "Interrupted", null, e);
        } finally {
            guard.release();
            long ms = (System.nanoTime() - started) / 1_000_000;
            record(accountId, method, masked, status, ms, usage, errorCode);
        }
    }

    private void record(Long accountId, String method, String maskedUrl, Integer status, long ms, ApiUsage usage,
                        String errorCode) {
        String endpoint = stripHost(maskedUrl);
        apiLog.atInfo()
                .addKeyValue("accountId", accountId)
                .addKeyValue("method", method)
                .addKeyValue("endpoint", endpoint)
                .addKeyValue("httpStatus", status)
                .addKeyValue("durationMs", ms)
                .addKeyValue("usagePercent", usage == null ? null : usage.percent())
                .addKeyValue("errorCode", errorCode)
                .log("{} {} -> {} in {} ms", method, endpoint, status, ms);
        try {
            recorder.record(new ApiCallRecorder.ApiCall(accountId, CorrelationId.current(), method, endpoint, status,
                    ms, usage, errorCode));
        } catch (RuntimeException e) {
            log.warn("Could not store api_call_log row: {}", e.toString());
        }
    }

    private GraphApiException classify(int status, JsonNode body) {
        JsonNode err = body.path("error");
        if (err.isMissingNode() || !err.isObject()) {
            Kind kind = status >= 500 ? Kind.TRANSIENT : status == 429 ? Kind.RATE_LIMITED : Kind.OTHER;
            return new GraphApiException(kind, status, null, null, "HTTP " + status + " from Graph API", null, null);
        }
        Integer code = err.has("code") ? err.path("code").asInt() : null;
        Integer subcode = err.has("error_subcode") ? err.path("error_subcode").asInt() : null;
        String message = err.path("message").asString("Graph API error");
        String type = err.path("type").asString("");
        boolean transientFlag = err.path("is_transient").asBoolean(false);

        Kind kind;
        if (code != null && TOKEN_CODES.contains(code)) {
            kind = Kind.TOKEN_INVALID;
        } else if (code != null && RATE_LIMIT_CODES.contains(code)) {
            kind = Kind.RATE_LIMITED;
        } else if (code != null && code == 10 && message.toLowerCase().contains("not enough viewers")) {
            kind = Kind.NOT_ENOUGH_DATA;
        } else if (code != null && (code == 10 || (code >= 200 && code < 300))) {
            kind = Kind.PERMISSION;
        } else if (transientFlag || (code != null && (code == 1 || code == 2)) || status >= 500) {
            // Code 1 is also Meta's answer to an unsupported breakdown; callers treat it like INVALID_REQUEST
            // after the retries are used up (see InstagramApi's per-metric fallback).
            kind = Kind.TRANSIENT;
        } else if ((code != null && code == 100) || "OAuthException".equals(type) && status == 400) {
            kind = Kind.INVALID_REQUEST;
        } else {
            kind = Kind.OTHER;
        }
        Instant retryAt = null;
        if (kind == Kind.RATE_LIMITED) {
            long minutes = err.path("error_data").path("estimated_time_to_regain_access").asLong(0);
            retryAt = minutes > 0 ? clock.instant().plusSeconds(minutes * 60) : null;
        }
        return new GraphApiException(kind, status, code, subcode, message, retryAt, null);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            return json.createObjectNode().put("_unparseable", true);
        }
    }

    private static String encode(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return new LinkedHashMap<>(params).entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** Stores {@code graph.instagram.com/v27.0/123/insights?metric=reach&access_token=***} without the scheme. */
    private static String stripHost(String url) {
        return url.replaceFirst("^https?://", "");
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
