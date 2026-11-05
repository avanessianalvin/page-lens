package com.pagelens.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.pagelens.core.config.PageLensProperties;

class TokenCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static TokenCipher cipher(String key) {
        return new TokenCipher(new PageLensProperties("logs", "", false, key, null));
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        TokenCipher c = cipher(KEY);
        byte[] blob = c.encrypt(7, "IGQVJ-secret-token");

        assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain("secret");
        assertThat(c.decrypt(7, blob)).isEqualTo("IGQVJ-secret-token");
    }

    @Test
    void usesFreshNoncePerEncryption() {
        TokenCipher c = cipher(KEY);
        assertThat(c.encrypt(1, "same")).isNotEqualTo(c.encrypt(1, "same"));
    }

    @Test
    void ciphertextIsBoundToItsAccount() {
        TokenCipher c = cipher(KEY);
        byte[] blob = c.encrypt(1, "IGQVJ-plaintext");
        assertThatThrownBy(() -> c.decrypt(2, blob)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("IGQVJ-plaintext");
    }

    @Test
    void detectsTampering() {
        TokenCipher c = cipher(KEY);
        byte[] blob = c.encrypt(1, "token");
        blob[blob.length - 1] ^= 1;
        assertThatThrownBy(() -> c.decrypt(1, blob)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingOrBadKeyFailsOnlyWhenUsed() {
        TokenCipher none = cipher("");
        assertThat(none.isConfigured()).isFalse();
        assertThatThrownBy(() -> none.encrypt(1, "x")).hasMessageContaining("PAGELENS_ENCRYPTION_KEY is not set");

        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])).encrypt(1, "x"))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> cipher("not base64!!").encrypt(1, "x")).hasMessageContaining("base64");
    }
}
