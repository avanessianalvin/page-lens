package com.pagelens.core.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pagelens.testsupport.FakeGraphApi;

class MetaOAuthClientTest {

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
    void authorizationUrlsCarryStateAndScopes() {
        String ig = kit.oauth.authorizationUrl(LoginType.INSTAGRAM_LOGIN, "st4te");
        assertThat(ig).startsWith(fake.url() + "/oauth/authorize?client_id=ig-app")
                .contains("state=st4te", "response_type=code",
                        "redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fauth%2Fcallback",
                        "instagram_business_manage_insights")
                .contains("instagram_business_content_publish") // publishing (Phase 3)
                .doesNotContain("ig-secret");

        String fb = kit.oauth.authorizationUrl(LoginType.FACEBOOK_LOGIN, "st4te");
        assertThat(fb).startsWith(fake.url() + "/v27.0/dialog/oauth?client_id=fb-app")
                .contains("pages_show_list", "instagram_manage_insights").doesNotContain("fb-secret");
    }

    @Test
    void instagramCodeExchangeYieldsLongLivedToken() {
        fake.onFixture("POST", "/oauth/access_token", "oauth-instagram-code.json");
        fake.onFixture("GET", "/access_token", "oauth-long-lived.json");

        var token = kit.oauth.instagramExchangeCode("AQBcode#_");

        assertThat(token.accessToken()).isEqualTo("LONG-LIVED-TOKEN");
        assertThat(token.igUserId()).isEqualTo("17841400000000001");
        assertThat(token.expiresAt()).isBetween(Instant.now().plusSeconds(59L * 86400), Instant.now().plusSeconds(61L * 86400));
        assertThat(token.toString()).doesNotContain("LONG-LIVED-TOKEN");

        var exchange = fake.requests("/oauth/access_token").getFirst();
        assertThat(FakeGraphApi.parse(exchange.body())).containsEntry("code", "AQBcode") // trailing #_ stripped
                .containsEntry("grant_type", "authorization_code").containsEntry("client_secret", "ig-secret");
        assertThat(fake.requests("/access_token").getFirst().query())
                .containsEntry("grant_type", "ig_exchange_token").containsEntry("access_token", "IG-SHORT-TOKEN");
        // Secrets reached Meta but never the call log.
        assertThat(kit.recorder.calls).allSatisfy(c -> assertThat(c.endpoint())
                .doesNotContain("ig-secret", "IG-SHORT-TOKEN", "AQBcode"));
    }

    @Test
    void facebookExchangeAndLinkedAccountDiscovery() {
        fake.on("GET", "/v27.0/oauth/access_token", r -> FakeGraphApi.Response.json(r.query().containsKey("code")
                ? "{\"access_token\":\"FB-SHORT\",\"token_type\":\"bearer\",\"expires_in\":3600}"
                : FakeGraphApi.fixture("oauth-long-lived.json")));
        fake.onFixture("GET", "/v27.0/me/accounts", "fb-me-accounts.json");

        var token = kit.oauth.facebookExchangeCode("fbcode");
        assertThat(token.accessToken()).isEqualTo("LONG-LIVED-TOKEN");
        assertThat(fake.requests("/v27.0/oauth/access_token").get(1).query())
                .containsEntry("grant_type", "fb_exchange_token").containsEntry("fb_exchange_token", "FB-SHORT");

        var linked = kit.oauth.facebookLinkedAccounts(token.accessToken());
        assertThat(linked).singleElement().satisfies(l -> {
            assertThat(l.igUserId()).isEqualTo("17841400000000099");
            assertThat(l.pageId()).isEqualTo("100000000000001");
        });
    }
}
