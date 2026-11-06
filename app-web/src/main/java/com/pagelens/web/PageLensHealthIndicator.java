package com.pagelens.web;

import java.util.List;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.pagelens.core.alerts.AlertService;
import com.pagelens.core.alerts.AlertService.Alert;
import com.pagelens.core.alerts.AlertService.Severity;

/**
 * {@code /actuator/health} component "pagelens": the number of open alerts (details only for the logged-in
 * owner). Alerts don't take the app DOWN: they are about Instagram data and backups, not about the app serving
 * requests.
 */
@Component("pagelens")
public class PageLensHealthIndicator implements HealthIndicator {

    private final AlertService alerts;

    public PageLensHealthIndicator(AlertService alerts) {
        this.alerts = alerts;
    }

    @Override
    public Health health() {
        List<Alert> current = alerts.current();
        long critical = current.stream().filter(a -> a.severity() == Severity.CRITICAL).count();
        return Health.up()
                .withDetail("alerts", current.size())
                .withDetail("critical", critical)
                .withDetail("kinds", current.stream().map(Alert::kind).distinct().toList())
                .build();
    }
}
