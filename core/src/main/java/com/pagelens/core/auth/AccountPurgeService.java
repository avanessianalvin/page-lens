package com.pagelens.core.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;

/**
 * Purge (plan section 12: "offer a purge command per account"): deletes everything PageLens synced for an account
 * (posts, insight history, comments, daily metrics, audience) but keeps the account connected. The next sync
 * starts from scratch. Nothing on Instagram changes. To also remove the account and its token, use disconnect.
 */
@Service
public class AccountPurgeService {

    private final AccountRepository accounts;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLogRepository audit;

    public AccountPurgeService(AccountRepository accounts, JdbcClient jdbc, TransactionTemplate tx,
                               AuditLogRepository audit) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
    }

    /**
     * @param confirmUsername must equal the account's username: a typed confirmation against accidents
     * @return rows deleted per table
     */
    public Map<String, Integer> purge(long accountId, String confirmUsername, Actor actor) {
        Account a = accounts.findById(accountId).orElseThrow(() -> new IllegalArgumentException("Unknown account " + accountId));
        String typed = confirmUsername == null ? "" : confirmUsername.trim().replaceFirst("^@", "");
        if (!typed.equalsIgnoreCase(a.username())) {
            throw new IllegalArgumentException("Type the account's username (" + a.username() + ") to confirm the purge");
        }
        Map<String, Integer> deleted = tx.execute(s -> {
            Map<String, Integer> d = new LinkedHashMap<>();
            d.put("comments", count("SELECT count(*) FROM comment c JOIN media m ON m.id = c.media_id WHERE m.account_id = :a", accountId));
            d.put("insight_snapshots", count("SELECT count(*) FROM media_insight_snapshot s JOIN media m ON m.id = s.media_id WHERE m.account_id = :a", accountId));
            d.put("posts", jdbc.sql("DELETE FROM media WHERE account_id = :a").param("a", accountId).update()); // cascades
            d.put("daily_metrics", jdbc.sql("DELETE FROM account_daily_metric WHERE account_id = :a").param("a", accountId).update());
            d.put("audience", jdbc.sql("DELETE FROM audience_snapshot WHERE account_id = :a").param("a", accountId).update());
            jdbc.sql("UPDATE ig_account SET last_synced_at = NULL WHERE id = :a").param("a", accountId).update();
            return d;
        });
        audit.record(actor, "account.purge", "@" + a.username(), deleted.toString(), false, "ok");
        return deleted;
    }

    private int count(String sql, long accountId) {
        return jdbc.sql(sql).param("a", accountId).query(Integer.class).single();
    }
}
