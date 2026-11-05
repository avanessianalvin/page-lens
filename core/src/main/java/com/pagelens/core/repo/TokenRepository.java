package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Encrypted tokens. This class never sees plaintext; {@code TokenService} encrypts and decrypts. */
@Repository
public class TokenRepository {

    public enum Status { VALID, EXPIRING, INVALID }

    /** {@link #toString()} omits the ciphertext. */
    public record StoredToken(long accountId, byte[] encryptedToken, int keyVersion, String tokenType, String scopes,
                              Instant expiresAt, Instant lastRefreshedAt, String status, Instant createdAt) {
        @Override
        public String toString() {
            return "StoredToken[accountId=" + accountId + ", expiresAt=" + expiresAt + ", status=" + status + "]";
        }
    }

    private final JdbcClient jdbc;

    public TokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(long accountId, byte[] encrypted, int keyVersion, String tokenType, String scopes,
                       Instant expiresAt, Instant refreshedAt) {
        jdbc.sql("""
                        INSERT INTO ig_token (account_id, encrypted_token, key_version, token_type, scopes, expires_at,
                                              last_refreshed_at, status)
                        VALUES (:id, :enc, :kv, :type, :scopes, :exp, :refreshed, 'VALID')
                        ON CONFLICT (account_id) DO UPDATE SET
                            encrypted_token = EXCLUDED.encrypted_token, key_version = EXCLUDED.key_version,
                            token_type = EXCLUDED.token_type, scopes = COALESCE(EXCLUDED.scopes, ig_token.scopes),
                            expires_at = EXCLUDED.expires_at, last_refreshed_at = EXCLUDED.last_refreshed_at,
                            status = 'VALID', updated_at = CURRENT_TIMESTAMP
                        """)
                .param("id", accountId).param("enc", encrypted).param("kv", keyVersion).param("type", tokenType)
                .param("scopes", scopes).param("exp", ts(expiresAt)).param("refreshed", ts(refreshedAt))
                .update();
    }

    public Optional<StoredToken> find(long accountId) {
        return jdbc.sql("SELECT * FROM ig_token WHERE account_id = :id").param("id", accountId)
                .query(TokenRepository::map).optional();
    }

    public List<StoredToken> findAll() {
        return jdbc.sql("SELECT * FROM ig_token ORDER BY account_id").query(TokenRepository::map).list();
    }

    public void markStatus(long accountId, Status status) {
        jdbc.sql("UPDATE ig_token SET status = :s, updated_at = CURRENT_TIMESTAMP WHERE account_id = :id")
                .param("s", status.name()).param("id", accountId).update();
    }

    private static StoredToken map(ResultSet rs, int row) throws SQLException {
        return new StoredToken(rs.getLong("account_id"), rs.getBytes("encrypted_token"), rs.getInt("key_version"),
                rs.getString("token_type"), rs.getString("scopes"), instant(rs, "expires_at"),
                instant(rs, "last_refreshed_at"), rs.getString("status"), instant(rs, "created_at"));
    }
}
