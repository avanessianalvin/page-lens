package com.pagelens.web.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.config.MetricCatalog;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AudienceRepository;
import com.pagelens.core.repo.AudienceRepository.AudienceBreakdown;
import com.pagelens.core.repo.DailyMetricRepository;
import com.pagelens.core.repo.DailyMetricRepository.DailyMetric;

@RestController
@RequestMapping("/api/accounts/{id}")
public class InsightController {

    private final AccountRepository accounts;
    private final DailyMetricRepository daily;
    private final AudienceRepository audience;
    private final MetricCatalog catalog;
    private final Clock clock;

    public InsightController(AccountRepository accounts, DailyMetricRepository daily, AudienceRepository audience,
                             MetricCatalog catalog, Clock clock) {
        this.accounts = accounts;
        this.daily = daily;
        this.audience = audience;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Daily account metrics. Days are UTC days (Meta's own day boundaries are undocumented). Default: last 30. */
    @GetMapping("/insights/daily")
    public List<DailyMetric> daily(@PathVariable long id,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireAccount(id);
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        return daily.range(id, start, end);
    }

    /** Latest audience snapshot per dimension (country, city, age, gender); groups under 30 are flagged. */
    @GetMapping("/audience")
    public List<AudienceBreakdown> audience(@PathVariable long id, @RequestParam(required = false) String dimension) {
        requireAccount(id);
        List<String> dims = dimension == null || dimension.isBlank()
                ? catalog.demographics().breakdowns() : List.of(dimension.toLowerCase());
        List<AudienceBreakdown> out = new ArrayList<>();
        for (String d : dims) {
            audience.latest(id, catalog.demographics().metric(), d).ifPresent(out::add);
        }
        return out;
    }

    private void requireAccount(long id) {
        accounts.findById(id).orElseThrow(() -> new NotFoundException("Account " + id));
    }
}
