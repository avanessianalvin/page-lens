package com.pagelens.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.pagelens.core.config.PageLensProperties;

/**
 * AES-256-GCM encryption for stored access tokens. Output layout: 12-byte nonce || ciphertext || 16-byte tag.
 * The account id is bound in as associated data, so a ciphertext copied onto another account's row won't decrypt.
 *
 * <p>The key comes from {@code PAGELENS_ENCRYPTION_KEY} (32 random bytes, base64). A missing or invalid key
 * only fails when a token is actually encrypted or decrypted, so the app can start before Meta setup is done.
 */
@Component
public class TokenCipher {

    public static final int KEY_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final String keyProblem;

    public TokenCipher(PageLensProperties properties) {
        SecretKeySpec parsed = null;
        String problem = null;
        String raw = properties.encryptionKey();
        if (raw == null || raw.isBlank()) {
            problem = "PAGELENS_ENCRYPTION_KEY is not set. Generate one with: openssl rand -base64 32";
        } else {
            try {
                byte[] bytes = Base64.getDecoder().decode(raw.trim());
                if (bytes.length != 32) {
                    problem = "PAGELENS_ENCRYPTION_KEY must decode to 32 bytes (got " + bytes.length + ")";
                } else {
                    parsed = new SecretKeySpec(bytes, "AES");
                }
            } catch (IllegalArgumentException e) {
                problem = "PAGELENS_ENCRYPTION_KEY is not valid base64";
            }
        }
        this.key = parsed;
        this.keyProblem = problem;
    }

    public boolean isConfigured() {
        return key != null;
    }

    public byte[] encrypt(long accountId, String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(accountId));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    public String decrypt(long accountId, byte[] blob) {
        requireKey();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES));
            cipher.updateAAD(aad(accountId));
            byte[] plain = cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong key, tampered data, or a row moved between accounts. Never include the data in the message.
            throw new IllegalStateException("Stored token could not be decrypted (wrong PAGELENS_ENCRYPTION_KEY?)", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(keyProblem);
        }
    }

    private static byte[] aad(long accountId) {
        return ("ig_token:" + accountId).getBytes(StandardCharsets.UTF_8);
    }
}
