package com.pagelens.core.instagram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.pagelens.core.config.HttpProperties;
import com.pagelens.core.config.MetaProperties;
import com.pagelens.core.instagram.IgModels.AccountRef;
import com.pagelens.testsupport.FakeGraphApi;

/** Wires the Graph client stack against a {@link FakeGraphApi} without Spring or a database. */
final class GraphTestKit {

    static final class MemoryRecorder implements ApiCallRecorder {
        final List<ApiCall> calls = Collections.synchronizedList(new ArrayList<>());
        volatile ApiUsage shared;

        @Override
        public void record(ApiCall call) {
            calls.add(call);
        }

        @Override
        public Optional<ApiUsage> latestUsage(Long accountId, Instant since) {
            return Optional.ofNullable(shared);
        }
    }

    final FakeGraphApi fake;
    final MemoryRecorder recorder = new MemoryRecorder();
    final MetaProperties meta;
    final GraphApiClient client;
    final RateLimitGuard guard;
    final InstagramApi api;
    final MetaOAuthClient oauth;

    GraphTestKit(FakeGraphApi fake) {
        this.fake = fake;
        HttpProperties http = new HttpProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 3,
                Duration.ofMillis(5), 75, 95);
        this.meta = new MetaProperties("v27.0", "fb-app", "fb-secret", "ig-app", "ig-secret",
                "http://localhost:8080/auth/callback",
                new MetaProperties.Endpoints(fake.url(), fake.url(), fake.url(), fake.url() + "/oauth/authorize", fake.url()));
        Clock clock = Clock.systemUTC();
        this.guard = new RateLimitGuard(recorder, http, clock);
        this.client = new GraphApiClient(http, meta, guard, recorder, new GraphJson(), clock);
        this.api = new InstagramApi(client);
        this.oauth = new MetaOAuthClient(client, meta, clock);
    }

    static AccountRef igAccount() {
        return new AccountRef(1L, "17841400000000001", LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123");
    }
}
