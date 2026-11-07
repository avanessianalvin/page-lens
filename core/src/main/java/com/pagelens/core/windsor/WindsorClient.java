package com.pagelens.core.windsor;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pagelens.core.config.WindsorProperties;
import com.pagelens.core.instagram.ApiCallRecorder;
import com.pagelens.core.instagram.ApiCallRecorder.ApiCall;
import com.pagelens.core.instagram.GraphJson;
import com.pagelens.core.instagram.SecretMasker;
import com.pagelens.core.logging.CorrelationId;
import com.pagelens.core.windsor.WindsorException.Kind;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads rows from the Windsor.ai connector API: {@code GET {base}/{connector}?api_key&fields&date_from&date_to},
 * answering {@code {"data":[{...}, ...]}}. Windsor only accepts the key as a query parameter, so every URL is
 * masked before it is logged or stored. Reads only; one retry after a network error or a 5xx.
 */
@Component
public class WindsorClient {

    private static final Logger log = LoggerFactory.getLogger(WindsorClient.class);
    private static final Logger apiLog = LoggerFactory.getLogger("pagelens.api-calls");

    private final WindsorProperties props;
    private final ApiCallRecorder recorder;
    private final JsonMapper json;
    private final HttpClient http;

    public WindsorClient(WindsorProperties props, ApiCallRecorder recorder, GraphJson json) {
        this.props = props;
        this.recorder = recorder;
        this.json = json.mapper();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean configured() {
        return props.configured();
    }

    /** Rows for {@code fields} between two dates, inclusive. For media, story and comment rows they filter on publish date. */
    public List<JsonNode> rows(Long accountId, List<String> fields, LocalDate from, LocalDate to) {
        if (!props.configured()) {
            throw new WindsorException(Kind.AUTH, "WINDSOR_API_KEY is not set", null);
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("api_key", props.apiKey());
        q.put("fields", String.join(",", fields));
        q.put("date_from", from.toString());
        q.put("date_to", to.toString());
        String url = props.baseUrl() + "/" + props.connector() + "?" + q.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            return send(accountId, url);
        } catch (WindsorException e) {
            if (!retryable(e)) {
                throw e;
            }
            log.debug("Windsor call failed, retrying once: {}", e.getMessage());
            return send(accountId, url);
        }
    }

    private static boolean retryable(WindsorException e) {
        return e.kind() == Kind.FAILED && (e.getCause() instanceof IOException
                || (e.getMessage() != null && e.getMessage().startsWith("HTTP 5")));
    }

    private List<JsonNode> send(Long accountId, String url) {
        String masked = SecretMasker.mask(url);
        long start = System.nanoTime();
        Integer status = null;
        String errorCode = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(props.requestTimeout())
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = res.statusCode();
            JsonNode body = parse(res.body());
            String error = errorMessage(body);
            String lower = error == null ? "" : error.toLowerCase(Locale.ROOT);
            if (status == 401 || status == 403 || lower.contains("not authorized") || lower.contains("api key")) {
                errorCode = "auth";
                throw new WindsorException(Kind.AUTH, "Windsor rejected the API key (" + error + ")", null);
            }
            if (status == 429) {
                errorCode = "rate_limited";
                throw new WindsorException(Kind.RATE_LIMITED, "Windsor request limit reached (" + error + ")", null);
            }
            if (status >= 400 || error != null) {
                errorCode = "error";
                throw new WindsorException(Kind.FAILED,
                        "HTTP " + status + ": " + (error != null ? error : "no details"), null);
            }
            JsonNode data = body.path("data");
            if (!data.isArray()) {
                errorCode = "bad_response";
                throw new WindsorException(Kind.FAILED, "Windsor response has no data array", null);
            }
            List<JsonNode> rows = new ArrayList<>(data.size());
            data.forEach(rows::add);
            return rows;
        } catch (IOException e) {
            errorCode = "network";
            throw new WindsorException(Kind.FAILED, "Windsor call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorCode = "interrupted";
            throw new WindsorException(Kind.FAILED, "Windsor call interrupted", e);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            recorder.record(new ApiCall(accountId, CorrelationId.current(), "GET", masked, status, ms, null, errorCode));
            apiLog.info("GET {} -> {} ({} ms){}", masked, status, ms, errorCode == null ? "" : " " + errorCode);
        }
    }

    private JsonNode parse(String body) {
        String text = body == null || body.isBlank() ? "{}" : body;
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            throw new WindsorException(Kind.FAILED, "Windsor returned non-JSON: "
                    + (text.length() > 200 ? text.substring(0, 200) : text), e);
        }
    }

    /** {@code {"error":"..."}} or {@code {"error":{"code":..,"message":..}}}; null when absent. */
    private static String errorMessage(JsonNode body) {
        JsonNode e = body.path("error");
        if (e.isMissingNode() || e.isNull()) {
            return null;
        }
        if (e.isString()) {
            return e.asString();
        }
        String msg = e.path("message").asString("");
        String code = e.path("code").asString("");
        return (code.isEmpty() ? "" : code + ": ") + (msg.isEmpty() ? e.toString() : msg);
    }
}
