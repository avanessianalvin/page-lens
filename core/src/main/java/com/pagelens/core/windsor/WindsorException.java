package com.pagelens.core.windsor;

import com.pagelens.core.instagram.SecretMasker;

/** A failed Windsor.ai call. Messages are secret-masked (the API key travels in the query string). */
public class WindsorException extends RuntimeException {

    public enum Kind {
        /** API key missing, wrong or revoked: every call will fail until it's fixed. */
        AUTH,
        /** Windsor's request quota (600/min, 10,000/day) is used up. */
        RATE_LIMITED,
        /** Anything else: a bad field combination, a 5xx, a timeout, an unreadable response. */
        FAILED
    }

    private final Kind kind;

    public WindsorException(Kind kind, String message, Throwable cause) {
        super(SecretMasker.mask(message), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Errors after which the rest of a sync run would fail the same way. */
    public boolean abortsRun() {
        return kind == Kind.AUTH || kind == Kind.RATE_LIMITED;
    }
}
