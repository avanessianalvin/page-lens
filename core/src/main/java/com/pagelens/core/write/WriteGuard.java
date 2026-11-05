package com.pagelens.core.write;

import org.springframework.stereotype.Component;

import com.pagelens.core.config.PageLensProperties;

/**
 * The master switch for write actions (plan sections 9 and 12): off unless {@code PAGELENS_WRITE_ACTIONS_ENABLED=true}.
 * Dry runs are always allowed (they change nothing) and report the switch as a blocking error.
 */
@Component
public class WriteGuard {

    public static final String DISABLED_MESSAGE = "Write actions are disabled. Set PAGELENS_WRITE_ACTIONS_ENABLED=true "
            + "in .env and restart PageLens to enable replying, hiding, deleting comments and publishing.";

    /** Thrown for a real (non-dry-run) write while the switch is off. */
    public static class WriteActionsDisabledException extends RuntimeException {
        public WriteActionsDisabledException() {
            super(DISABLED_MESSAGE);
        }
    }

    private final PageLensProperties props;

    public WriteGuard(PageLensProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.writeActionsEnabled();
    }

    /** For a real write: throws when disabled. For a dry run: records the problem in the report instead. */
    public void check(boolean dryRun, WriteResult.Builder report) {
        if (enabled()) {
            return;
        }
        if (!dryRun) {
            throw new WriteActionsDisabledException();
        }
        report.error(DISABLED_MESSAGE);
    }
}
