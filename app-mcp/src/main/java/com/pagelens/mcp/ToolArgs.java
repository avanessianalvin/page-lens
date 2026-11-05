package com.pagelens.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads tool arguments defensively (clients send numbers as int, long, double or string) and builds schemas. */
final class ToolArgs {

    private final Map<String, Object> args;

    ToolArgs(Map<String, Object> args) {
        this.args = args == null ? Map.of() : args;
    }

    String str(String name) {
        Object v = args.get(name);
        return v == null ? null : v.toString().trim();
    }

    int integer(String name, int def, int min, int max) {
        Object v = args.get(name);
        int n;
        if (v == null || v.toString().isBlank()) {
            n = def;
        } else if (v instanceof Number num) {
            n = num.intValue();
        } else {
            try {
                n = (int) Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number");
            }
        }
        return Math.clamp(n, min, max);
    }

    long requiredLong(String name) {
        Object v = args.get(name);
        if (v instanceof Number num) {
            return num.longValue();
        }
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a PageLens id (a number from list_posts)");
        }
    }

    // ------------------------------------------------------------------------------------------ JSON Schema

    static final class Schema {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new java.util.ArrayList<>();

        Schema prop(String name, String type, String description) {
            properties.put(name, Map.of("type", type, "description", description));
            return this;
        }

        Schema enumProp(String name, List<String> values, String description) {
            properties.put(name, Map.of("type", "string", "enum", values, "description", description));
            return this;
        }

        Schema required(String name) {
            required.add(name);
            return this;
        }

        /** The common account selector. */
        Schema account() {
            return prop("account", "string", "Account username (with or without @) or PageLens account id. "
                    + "May be omitted when only one account is connected.");
        }

        /** The common time window. */
        Schema period(String defaultPeriod) {
            enumProp("period", com.pagelens.core.analysis.Periods.NAMES,
                    "Time window counted back from now (default " + defaultPeriod + "). Ignored if from/to are given.");
            prop("from", "string", "Start date YYYY-MM-DD (inclusive, owner's time zone).");
            return prop("to", "string", "End date YYYY-MM-DD (inclusive, owner's time zone).");
        }

        Map<String, Object> build() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", properties);
            if (!required.isEmpty()) {
                s.put("required", required);
            }
            s.put("additionalProperties", false);
            return s;
        }
    }
}
