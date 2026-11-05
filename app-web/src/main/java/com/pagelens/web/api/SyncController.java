package com.pagelens.web.api;

import java.util.List;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.config.PageLensProperties;
import com.pagelens.core.config.SyncProperties;
import com.pagelens.core.instagram.ApiUsage;
import com.pagelens.core.instagram.RateLimitGuard;
import com.pagelens.core.repo.SyncRunRepository;
import com.pagelens.core.repo.SyncRunRepository.SyncRun;

@RestController
@RequestMapping("/api")
public class SyncController {

    public record RateLimit(Long accountId, Integer usagePercent, java.time.Instant blockedUntil) {
    }

    public record Settings(String timeZone, boolean writeActionsEnabled, String syncCron, String storiesCron) {
    }

    private final SyncRunRepository runs;
    private final RateLimitGuard guard;
    private final PageLensProperties props;
    private final SyncProperties sync;

    public SyncController(SyncRunRepository runs, RateLimitGuard guard, PageLensProperties props, SyncProperties sync) {
        this.runs = runs;
        this.guard = guard;
        this.props = props;
        this.sync = sync;
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

    /** Read-only settings the UI needs (display zone etc.). Editing settings arrives with the Settings page. */
    @GetMapping("/settings")
    public Settings settings() {
        return new Settings(props.zoneId().getId(), props.writeActionsEnabled(), sync.cron(), sync.storiesCron());
    }
}
