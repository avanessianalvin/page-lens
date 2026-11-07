package com.pagelens.core.repo;

import static com.pagelens.core.repo.Db.instant;
import static com.pagelens.core.repo.Db.lng;
import static com.pagelens.core.repo.Db.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.pagelens.core.instagram.IgModels.IgProfile;
import com.pagelens.core.instagram.LoginType;

@Repository
public class AccountRepository {

    public enum Status { ACTIVE, NEEDS_RECONNECT }

    public record Account(long id, String igUserId, LoginType loginType, String facebookPageId, String username,
                          String displayName, String accountType, Long followersCount, Long followsCount,
                          Long mediaCount, String profilePictureUrl, String status, String statusMessage,
                          Instant lastSyncedAt, Instant createdAt) {
    }

    private static final String COLUMNS = """
            id, ig_user_id, login_type, facebook_page_id, username, display_name, account_type, followers_count,
            follows_count, media_count, profile_picture_url, status, status_message, last_synced_at, created_at
            """;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates or re-activates the account for this IG user id; returns its id. */
    public long upsertConnected(String igUserId, LoginType loginType, String facebookPageId, String username) {
        return jdbc.sql("""
                        INSERT INTO ig_account (ig_user_id, login_type, facebook_page_id, username, status)
                        VALUES (:igUserId, :loginType, :pageId, :username, 'ACTIVE')
                        ON CONFLICT (ig_user_id) DO UPDATE SET
                            login_type = EXCLUDED.login_type,
                            facebook_page_id = EXCLUDED.facebook_page_id,
                            username = COALESCE(EXCLUDED.username, ig_account.username),
                            status = 'ACTIVE', status_message = NULL, updated_at = CURRENT_TIMESTAMP
                        RETURNING id
                        """)
                .param("igUserId", igUserId).param("loginType", loginType.name())
                .param("pageId", facebookPageId).param("username", username == null ? igUserId : username)
                .query(Long.class).single();
    }

    /**
     * Adds an account fed by a read-only source (Windsor.ai or CSV import). An existing account keeps a direct
     * Meta connection: only IMPORT → WINDSOR upgrades its type. Returns the account id.
     */
    public long upsertReadOnlySource(String igUserId, LoginType source, String username) {
        if (source.direct()) {
            throw new IllegalArgumentException("Use upsertConnected for " + source);
        }
        return jdbc.sql("""
                        INSERT INTO ig_account (ig_user_id, login_type, username, status)
                        VALUES (:igUserId, :source, :username, 'ACTIVE')
                        ON CONFLICT (ig_user_id) DO UPDATE SET
                            login_type = CASE WHEN ig_account.login_type = 'IMPORT' THEN EXCLUDED.login_type
                                              ELSE ig_account.login_type END,
                            username = CASE WHEN ig_account.username = ig_account.ig_user_id THEN EXCLUDED.username
                                            ELSE ig_account.username END,
                            updated_at = CURRENT_TIMESTAMP
                        RETURNING id
                        """)
                .param("igUserId", igUserId).param("source", source.name())
                .param("username", username == null ? igUserId : username)
                .query(Long.class).single();
    }

    public Optional<Account> findByIgUserId(String igUserId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ig_account WHERE ig_user_id = :id").param("id", igUserId)
                .query(AccountRepository::map).optional();
    }

    public void updateProfile(long id, IgProfile p) {
        jdbc.sql("""
                        UPDATE ig_account SET
                            username = COALESCE(:username, username), display_name = :name,
                            account_type = COALESCE(:accountType, account_type),
                            profile_picture_url = :pic, followers_count = :followers, follows_count = :follows,
                            media_count = :media, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        """)
                .param("username", p.username()).param("name", p.name()).param("accountType", p.accountType())
                .param("pic", p.profilePictureUrl()).param("followers", p.followersCount())
                .param("follows", p.followsCount()).param("media", p.mediaCount()).param("id", id)
                .update();
    }

    public void markStatus(long id, Status status, String message) {
        jdbc.sql("UPDATE ig_account SET status = :status, status_message = :msg, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("status", status.name()).param("msg", message).param("id", id).update();
    }

    public void touchSynced(long id, Instant at) {
        jdbc.sql("UPDATE ig_account SET last_synced_at = :at WHERE id = :id").param("at", ts(at)).param("id", id).update();
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ig_account WHERE id = :id").param("id", id)
                .query(AccountRepository::map).optional();
    }

    public List<Account> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ig_account ORDER BY username").query(AccountRepository::map).list();
    }

    public List<Account> findActive() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ig_account WHERE status = 'ACTIVE' ORDER BY id")
                .query(AccountRepository::map).list();
    }

    /** Locks the account row for the rest of the current transaction (serialises sync starts across processes). */
    public void lockForUpdate(long id) {
        jdbc.sql("SELECT id FROM ig_account WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).optional();
    }

    /** Deletes the account and, via ON DELETE CASCADE, all of its data (Meta policy: purge on disconnect). */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM ig_account WHERE id = :id").param("id", id).update() > 0;
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getLong("id"), rs.getString("ig_user_id"), LoginType.valueOf(rs.getString("login_type")),
                rs.getString("facebook_page_id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("account_type"), lng(rs, "followers_count"), lng(rs, "follows_count"),
                lng(rs, "media_count"), rs.getString("profile_picture_url"), rs.getString("status"),
                rs.getString("status_message"), instant(rs, "last_synced_at"), instant(rs, "created_at"));
    }
}
