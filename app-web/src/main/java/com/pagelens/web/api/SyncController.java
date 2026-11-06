package com.pagelens.web.api;

import java.util.List;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.instagram.ApiUsage;
import com.pagelens.core.instagram.RateLimitGuard;
import com.pagelens.core.repo.SyncRunRepository;
import com.pagelens.core.repo.SyncRunRepository.SyncRun;

/** Sync runs and rate-limit state. Settings live in {@link OpsController}. */
@RestController
@RequestMapping("/api")
public class SyncController {

    public record RateLimit(Long accountId, Integer usagePercent, java.time.Instant blockedUntil) {
    }

    private final SyncRunRepository runs;
    private final RateLimitGuard guard;

    public SyncController(SyncRunRepository runs, RateLimitGuard guard) {
        this.runs = runs;
        this.guard = guard;
    }

    @GetMapping("/sync-runs")
    public List<SyncRun> recent(@RequestParam(required = false) Long accountId,
                                @RequestParam(defaultValue = "50") int limit) {
        return runs.recent(accountId, limit);
    }

    @GetMapping("/sync-runs/{id}")
    public SyncRun get(@PathVariable long id) {
        return runs.find(id).orElseThrow(() -> new NotFoundException("Sync run " + id));
    }

    /** Latest known Meta usage for an account (shared across PageLens processes). */
    @GetMapping("/rate-limit")
    public RateLimit rateLimit(@RequestParam(required = false) Long accountId) {
        Optional<ApiUsage> u = guard.current(accountId);
        return new RateLimit(accountId, u.map(ApiUsage::percent).orElse(null), u.map(ApiUsage::regainAt).orElse(null));
    }
}
