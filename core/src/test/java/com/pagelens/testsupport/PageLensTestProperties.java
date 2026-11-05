package com.pagelens.testsupport;

import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Points a test context at a {@link FakeGraphApi} with fast retries, a fixed encryption key and no scheduler. */
public final class PageLensTestProperties {

    /** All-zero AES key: tests only. */
    public static final String TEST_ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private PageLensTestProperties() {
    }

    public static void apply(DynamicPropertyRegistry r, FakeGraphApi fake) {
        r.add("pagelens.meta.endpoints.graph-instagram", fake::url);
        r.add("pagelens.meta.endpoints.graph-facebook", fake::url);
        r.add("pagelens.meta.endpoints.instagram-api", fake::url);
        r.add("pagelens.meta.endpoints.instagram-authorize", () -> fake.url() + "/oauth/authorize");
        r.add("pagelens.meta.endpoints.facebook-dialog", fake::url);
        r.add("pagelens.meta.app-id", () -> "fb-app");
        r.add("pagelens.meta.app-secret", () -> "fb-secret");
        r.add("pagelens.meta.instagram-app-id", () -> "ig-app");
        r.add("pagelens.meta.instagram-app-secret", () -> "ig-secret");
        r.add("pagelens.encryption-key", () -> TEST_ENCRYPTION_KEY);
        r.add("pagelens.http.initial-backoff", () -> "5ms");
        r.add("pagelens.sync.enabled", () -> "false");
        r.add("pagelens.sync.backfill-days", () -> "3");
        r.add("pagelens.log-dir", () -> "target/test-logs");
    }
}
