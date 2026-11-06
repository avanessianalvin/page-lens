package com.pagelens.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pagelens.core.instagram.LoginType;
import com.pagelens.testsupport.FakeGraphApi;
import com.pagelens.testsupport.FakeGraphApi.Response;
import com.pagelens.testsupport.PageLensTestProperties;
import com.pagelens.testsupport.PostgresTestcontainer;

/** The daily token check: refresh, expiry warning, invalidation (plan section 5). */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@Testcontainers(disabledWithoutDocker = true)
class TokenServiceTest {

    static final FakeGraphApi fake = FakeGraphApi.start();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PageLensTestProperties.apply(r, fake);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired
    TokenService tokens;
    @Autowired
    AccountConnectionService connections;
    @Autowired
    JdbcClient jdbc;

    long accountId;

    @BeforeEach
    void setUp() {
        fake.reset();
        jdbc.sql("DELETE FROM ig_account").update();
        fake.onFixture("GET", "/v27.0/me", "ig-me.json");
        accountId = connections.connectWithToken(LoginType.INSTAGRAM_LOGIN, "TEST-TOKEN-123").getFirst().id();
    }

    @Test
    void refreshesInstagramTokensOlderThanTheRefreshAge() {
        fake.onJson("GET", "/refresh_access_token",
                "{\"access_token\":\"REFRESHED-TOKEN\",\"token_type\":\"bearer\",\"expires_in\":5184000}");
        ageToken(8);

        tokens.checkAll();

        assertThat(fake.requests("/refresh_access_token")).singleElement().satisfies(r ->
                assertThat(r.query()).containsEntry("grant_type", "ig_refresh_token").containsEntry("access_token", "TEST-TOKEN-123"));
        var health = tokens.health(accountId);
        assertThat(health.status()).isEqualTo("VALID");
        assertThat(health.daysLeft()).isBetween(59L, 60L);
        // The new token is what's used from now on (and it's stored encrypted).
        assertThat(tokens.ref(jdbcAccount()).accessToken()).isEqualTo("REFRESHED-TOKEN");
    }

    @Test
    void freshTokensAreNotRefreshed() {
        ageToken(2);
        tokens.checkAll();
        assertThat(fake.requests("/refresh_access_token")).isEmpty();
    }

    @Test
    void tokenExpiringSoonIsMarkedExpiringWhenRefreshFails() {
        fake.on("GET", "/refresh_access_token", r -> Response.error(500, 2, "Service temporarily unavailable"));
        ageToken(8);
        jdbc.sql("UPDATE ig_token SET expires_at = now() + interval '3 days'").update();

        tokens.checkAll();

        assertThat(tokens.health(accountId).status()).isEqualTo("EXPIRING");
        assertThat(account("status")).isEqualTo("ACTIVE"); // still usable
    }

    @Test
    void rejectedOrExpiredTokensRequireReconnect() {
        fake.on("GET", "/refresh_access_token", r -> Response.error(400, 190, "Error validating access token"));
        ageToken(8);
        tokens.checkAll();
        assertThat(tokens.health(accountId).status()).isEqualTo("INVALID");
        assertThat(account("status")).isEqualTo("NEEDS_RECONNECT");

        // Expired by date: no API call needed.
        jdbc.sql("UPDATE ig_token SET status = 'VALID', expires_at = now() - interval '1 hour'").update();
        jdbc.sql("UPDATE ig_account SET status = 'ACTIVE'").update();
        int calls = fake.requests().size();
        tokens.checkAll();
        assertThat(account("status")).isEqualTo("NEEDS_RECONNECT");
        assertThat(fake.requests()).hasSize(calls);
    }

    @Test
    void facebookTokensAreNeverRefreshedButWarnBeforeExpiry() {
        jdbc.sql("UPDATE ig_account SET login_type = 'FACEBOOK_LOGIN'").update();
        ageToken(30);
        jdbc.sql("UPDATE ig_token SET expires_at = now() + interval '5 days'").update();

        tokens.checkAll();

        assertThat(fake.requests("/refresh_access_token")).isEmpty();
        assertThat(tokens.health(accountId).status()).isEqualTo("EXPIRING");
        assertThat(tokens.health(accountId).expiringSoon()).isTrue();
    }

    private void ageToken(int days) {
        jdbc.sql("UPDATE ig_token SET last_refreshed_at = :t, created_at = :t")
                .param("t", Instant.now().minus(days, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC)).update();
    }

    private String account(String column) {
        return jdbc.sql("SELECT " + column + " FROM ig_account WHERE id = :id").param("id", accountId)
                .query(String.class).single();
    }

    private com.pagelens.core.repo.AccountRepository.Account jdbcAccount() {
        return new com.pagelens.core.repo.AccountRepository(jdbc).findById(accountId).orElseThrow();
    }
}
