package com.pagelens.core.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a write action or its dry run. {@code ok=false} with errors means nothing was changed on Instagram.
 *
 * @param dryRun   true when only validated (nothing sent to Instagram)
 * @param errors   blocking problems, written for the owner
 * @param warnings non-blocking notes (e.g. the media URL could not be pre-checked)
 * @param details  what was (or would be) done: ids, counts, quota
 */
public record WriteResult(boolean dryRun, boolean ok, List<String> errors, List<String> warnings,
                          Map<String, Object> details) {

    /** Mutable builder used while validating. */
    public static final class Builder {
        private final boolean dryRun;
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, Object> details = new LinkedHashMap<>();

        public Builder(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public Builder error(String e) {
            errors.add(e);
            return this;
        }

        public Builder warn(String w) {
            warnings.add(w);
            return this;
        }

        public Builder detail(String k, Object v) {
            if (v != null) {
                details.put(k, v);
            }
            return this;
        }

        public boolean dryRun() {
            return dryRun;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public WriteResult build() {
            return new WriteResult(dryRun, errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings),
                    Map.copyOf(details));
        }
    }
}
