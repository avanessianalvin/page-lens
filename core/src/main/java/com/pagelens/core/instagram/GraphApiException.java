package com.pagelens.core.instagram;

import java.time.Instant;

/**
 * A failed Graph API call, classified so callers can react: reconnect, back off, skip a metric, retry.
 * Messages are already secret-masked.
 */
public class GraphApiException extends RuntimeException {

    public enum Kind {
        /** Token expired, revoked or invalid (code 190 and friends): the account must be reconnected. */
        TOKEN_INVALID,
        /** Missing permission (codes 10, 200-299). */
        PERMISSION,
        /** Rate limit reached (codes 4, 17, 32, 613, 80002). Stop until {@link #retryAt()}. */
        RATE_LIMITED,
        /** Bad parameter, e.g. an unsupported metric or field (code 100, or the generic "unknown error"). */
        INVALID_REQUEST,
        /** Not enough data to show insights, e.g. a story with fewer than 5 viewers. */
        NOT_ENOUGH_DATA,
        /** Network error, 5xx, or a transient Meta error: worth retrying. */
        TRANSIENT,
        OTHER
    }

    private final Kind kind;
    private final int httpStatus;
    private final Integer code;
    private final Integer subcode;
    private final Instant retryAt;

    public GraphApiException(Kind kind, int httpStatus, Integer code, Integer subcode, String message,
                             Instant retryAt, Throwable cause) {
        super(SecretMasker.mask(message), cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
        this.subcode = subcode;
        this.retryAt = retryAt;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Integer code() {
        return code;
    }

    public Integer subcode() {
        return subcode;
    }

    /** For {@link Kind#RATE_LIMITED}: when calls may resume, if Meta said so. */
    public Instant retryAt() {
        return retryAt;
    }

    /** Short identifier stored in api_call_log.error_code, e.g. {@code 190} or {@code 613/1996}. */
    public String errorCode() {
        if (code == null) {
            return httpStatus > 0 ? "HTTP_" + httpStatus : kind.name();
        }
        return subcode == null ? code.toString() : code + "/" + subcode;
    }
}
