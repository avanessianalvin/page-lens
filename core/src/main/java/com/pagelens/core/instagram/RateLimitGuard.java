package com.pagelens.core.instagram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pagelens.core.config.HttpProperties;

/**
 * One rate budget shared by the UI, the scheduler and the MCP process (plan section 12).
 *
 * <ul>
 *   <li>Within a process: at most {@value #MAX_CONCURRENT} Graph calls in flight.</li>
 *   <li>Across processes: the latest usage any process recorded in api_call_log (cached briefly) is
 *       combined with this process's own view.</li>
 *   <li>At {@code slow-down-at-percent}, calls are spaced out. At {@code stop-at-percent}, or while Meta says
 *       access is blocked, calls fail fast with {@link GraphApiException.Kind#RATE_LIMITED}, because failed
 *       calls still count against the budget.</li>
 * </ul>
 */
@Component
public class RateLimitGuard {

    static final int MAX_CONCURRENT = 2;
    private static final Duration SHARED_STATE_TTL = Duration.ofSeconds(15);
    private static final Duration SHARED_STATE_WINDOW = Duration.ofHours(1);
    private static final Duration MAX_SLOW_DOWN_DELAY = Duration.ofSeconds(5);
    private static final Duration DEFAULT_BLOCK = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(RateLimitGuard.class);

    private record State(int percent, Instant regainAt, Instant checkedSharedAt) {
    }

    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT, true);
    private final Map<Long, State> states = new ConcurrentHashMap<>();
    private final ApiCallRecorder recorder;
    private final HttpProperties http;
    private final Clock clock;

    public RateLimitGuard(ApiCallRecorder recorder, HttpProperties http, Clock clock) {
        this.recorder = recorder;
        this.http = http;
        this.clock = clock;
    }

    /** Blocks for a permit (and any slow-down delay). Callers must call {@link #release()} afterwards. */
    public void acquire(Long accountId) {
        State state = refreshShared(key(accountId));
        Instant now = clock.instant();
        if (state.regainAt() != null && state.regainAt().isAfter(now)) {
            throw blocked(state.regainAt());
        }
        if (state.percent() >= http.stopAtPercent()) {
            throw blocked(now.plus(DEFAULT_BLOCK));
        }
        if (state.percent() >= http.slowDownAtPercent()) {
            double range = Math.max(1, http.stopAtPercent() - http.slowDownAtPercent());
            long delayMs = (long) (MAX_SLOW_DOWN_DELAY.toMillis() * (state.percent() - http.slowDownAtPercent()) / range);
            log.info("Rate usage at {}%, delaying Graph call by {} ms", state.percent(), delayMs);
            sleep(delayMs);
        }
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphApiException(GraphApiException.Kind.TRANSIENT, 0, null, null, "Interrupted", null, e);
        }
    }

    public void release() {
        inFlight.release();
    }

    /** Records usage reported by a response (or by a rate-limit error). */
    public void observe(Long accountId, ApiUsage usage) {
        if (usage == null) {
            return;
        }
        states.compute(key(accountId), (k, old) -> new State(usage.percent(),
                later(usage.regainAt(), old == null ? null : old.regainAt()),
                old == null ? Instant.EPOCH : old.checkedSharedAt()));
    }

    /** Marks the account blocked until {@code until} (from a RATE_LIMITED error). */
    public void blockUntil(Long accountId, Instant until) {
        Instant effective = until != null ? until : clock.instant().plus(DEFAULT_BLOCK);
        states.compute(key(accountId), (k, old) -> new State(old == null ? 100 : Math.max(old.percent(), 100),
                later(effective, old == null ? null : old.regainAt()),
                old == null ? Instant.EPOCH : old.checkedSharedAt()));
    }

    /** Current view for the UI's rate-limit endpoint. */
    public Optional<ApiUsage> current(Long accountId) {
        State s = refreshShared(key(accountId));
        return s.checkedSharedAt() == Instant.EPOCH && s.percent() == 0 && s.regainAt() == null
                ? Optional.empty() : Optional.of(new ApiUsage(s.percent(), s.regainAt(), null));
    }

    private State refreshShared(long key) {
        Instant now = clock.instant();
        return states.compute(key, (k, old) -> {
            State base = old != null ? old : new State(0, null, Instant.EPOCH);
            if (Duration.between(base.checkedSharedAt(), now).compareTo(SHARED_STATE_TTL) < 0) {
                return base;
            }
            Optional<ApiUsage> shared;
            try {
                shared = recorder.latestUsage(k == 0 ? null : k, now.minus(SHARED_STATE_WINDOW));
            } catch (RuntimeException e) {
                log.warn("Could not read shared rate-limit state: {}", e.toString());
                shared = Optional.empty();
            }
            int percent = Math.max(base.percent(), shared.map(ApiUsage::percent).orElse(0));
            Instant regain = later(base.regainAt(), shared.map(ApiUsage::regainAt).orElse(null));
            if (regain != null && !regain.isAfter(now)) {
                regain = null;
                percent = shared.map(ApiUsage::percent).orElse(0); // block expired: trust fresh data only
            }
            return new State(percent, regain, now);
        });
    }

    private static GraphApiException blocked(Instant until) {
        return new GraphApiException(GraphApiException.Kind.RATE_LIMITED, 0, null, null,
                "Instagram rate limit reached; calls paused until " + until, until, null);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static long key(Long accountId) {
        return accountId == null ? 0L : accountId;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
