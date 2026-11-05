package com.pagelens.web.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Login rate limiting (plan section 5): after {@value #MAX_FAILURES} failures within the window, the client
 * address is locked out for {@link #LOCKOUT}. Kept in memory: a restart clears it, which is fine for a
 * localhost-only app.
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCKOUT = Duration.ofMinutes(15);

    private record Attempts(int failures, Instant firstFailure, Instant lockedUntil) {
    }

    private final Map<String, Attempts> byClient = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** When the client may try again, or {@code null} if it is not locked. */
    public Instant lockedUntil(String client) {
        Attempts a = byClient.get(client);
        Instant now = clock.instant();
        return a != null && a.lockedUntil() != null && a.lockedUntil().isAfter(now) ? a.lockedUntil() : null;
    }

    public void failed(String client) {
        Instant now = clock.instant();
        byClient.compute(client, (k, a) -> {
            if (a == null || a.firstFailure().plus(WINDOW).isBefore(now)) {
                return new Attempts(1, now, null);
            }
            int n = a.failures() + 1;
            return new Attempts(n, a.firstFailure(), n >= MAX_FAILURES ? now.plus(LOCKOUT) : a.lockedUntil());
        });
    }

    public void succeeded(String client) {
        byClient.remove(client);
    }
}
