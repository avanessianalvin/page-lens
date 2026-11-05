package com.pagelens.core.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pagelens.core.instagram.GraphApiException.Kind;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;

class GraphApiClientTest {

    FakeGraphApi fake;
    GraphTestKit kit;

    @BeforeEach
    void setUp() {
        fake = FakeGraphApi.start();
        kit = new GraphTestKit(fake);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void sendsTokenButRecordsOnlyMaskedEndpoint() {
        fake.onJson("GET", "/v27.0/me", "{\"user_id\":\"1\"}");
        kit.client.get(1L, kit.client.graphBase(LoginType.INSTAGRAM_LOGIN) + "/me",
                Map.of("fields", "user_id", "access_token", "TEST-TOKEN-123"));

        assertThat(fake.requests("/v27.0/me").getFirst().query()).containsEntry("access_token", "TEST-TOKEN-123");
        assertThat(kit.recorder.calls).singleElement().satisfies(c -> {
            assertThat(c.endpoint()).contains("access_token=***").doesNotContain("TEST-TOKEN-123");
            assertThat(c.httpStatus()).isEqualTo(200);
            assertThat(c.method()).isEqualTo("GET");
        });
    }

    @Test
    void classifiesTokenErrors() {
        fake.on("GET", "/v27.0/me", r -> Response.error(400, 190, "Error validating access token"));
        assertThatThrownBy(() -> get("/me"))
                .isInstanceOfSatisfying(GraphApiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TOKEN_INVALID);
                    assertThat(e.errorCode()).isEqualTo("190");
                });
        assertThat(fake.requests("/v27.0/me")).hasSize(1); // never retried
    }

    @Test
    void retriesTransientErrorsWithBackoffThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        fake.on("GET", "/v27.0/me", r -> calls.incrementAndGet() < 3
                ? new Response(500, "{\"error\":{\"message\":\"Service temporarily unavailable\",\"code\":2,\"is_transient\":true}}", Map.of())
                : Response.json("{\"user_id\":\"1\"}"));

        assertThat(get("/me").path("user_id").asString()).isEqualTo("1");
        assertThat(kit.recorder.calls).hasSize(3);
        assertThat(kit.recorder.calls.get(0).errorCode()).isEqualTo("2");
    }

    @Test
    void givesUpAfterMaxAttempts() {
        fake.on("GET", "/v27.0/me", r -> new Response(503, "", Map.of()));
        assertThatThrownBy(() -> get("/me")).isInstanceOfSatisfying(GraphApiException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
        assertThat(fake.requests("/v27.0/me")).hasSize(3);
    }

    @Test
    void rateLimitErrorBlocksFurtherCallsWithoutHittingMeta() {
        fake.on("GET", "/v27.0/me", r -> new Response(400,
                "{\"error\":{\"message\":\"Application request limit reached\",\"code\":80002,"
                        + "\"error_data\":{\"estimated_time_to_regain_access\":30}}}",
                Map.of("X-Business-Use-Case-Usage",
                        "{\"1\":[{\"type\":\"instagram\",\"call_count\":100,\"estimated_time_to_regain_access\":30}]}")));

        assertThatThrownBy(() -> get("/me")).isInstanceOfSatisfying(GraphApiException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(e.retryAt()).isAfter(Instant.now().plusSeconds(25 * 60));
        });
        assertThatThrownBy(() -> get("/me")).isInstanceOfSatisfying(GraphApiException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        assertThat(fake.requests("/v27.0/me")).hasSize(1); // second call stopped locally
        assertThat(kit.recorder.calls.getFirst().usage().regainAt()).isNotNull();
    }

    @Test
    void sharedUsageFromAnotherProcessStopsCalls() {
        kit.recorder.shared = new ApiUsage(97, null, null);
        assertThatThrownBy(() -> get("/me")).isInstanceOfSatisfying(GraphApiException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void notEnoughViewersIsItsOwnKind() {
        fake.on("GET", "/v27.0/42/insights", r -> Response.error(400, 10, "(#10) Not enough viewers for the media to show insights"));
        assertThatThrownBy(() -> get("/42/insights")).isInstanceOfSatisfying(GraphApiException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.NOT_ENOUGH_DATA));
    }

    @Test
    void errorMessagesAreMasked() {
        fake.on("GET", "/v27.0/me", r -> Response.error(400, 100, "Bad request for access_token=LEAKED"));
        assertThatThrownBy(() -> get("/me")).hasMessageNotContaining("LEAKED").hasMessageContaining("access_token=***");
    }

    private tools.jackson.databind.JsonNode get(String path) {
        return kit.client.get(1L, kit.client.graphBase(LoginType.INSTAGRAM_LOGIN) + path,
                Map.of("access_token", "TEST-TOKEN-123"));
    }
}
