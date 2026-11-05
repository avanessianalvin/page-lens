package com.pagelens.core.logging;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Correlation id carried in the logging MDC so every line from one HTTP request, sync run or MCP call
 * can be grouped. Structured JSON logs include MDC entries automatically.
 *
 * <pre>{@code
 * try (var ignored = CorrelationId.open("sync")) {
 *     ... // every log line in here carries correlationId=sync-<uuid>
 * }
 * }</pre>
 */
public final class CorrelationId {

    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** Starts a new correlation scope with a generated id, e.g. {@code sync-3f2a...}. */
    public static MDC.MDCCloseable open(String prefix) {
        return use(prefix + "-" + UUID.randomUUID());
    }

    /** Starts a correlation scope with an id supplied by the caller (e.g. an incoming request header). */
    public static MDC.MDCCloseable use(String id) {
        return MDC.putCloseable(MDC_KEY, id);
    }

    /** The id of the current scope, or {@code null} outside any scope. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
