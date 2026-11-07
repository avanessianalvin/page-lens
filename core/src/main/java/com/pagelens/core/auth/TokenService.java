package com.pagelens.core.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pagelens.core.config.SyncProperties;
import com.pagelens.core.crypto.TokenCipher;
import com.pagelens.core.instagram.GraphApiException;
import com.pagelens.core.instagram.IgModels.AccountRef;
import com.pagelens.core.instagram.IgModels.IssuedToken;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.instagram.MetaOAuthClient;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.TokenRepository;
import com.pagelens.core.repo.TokenRepository.StoredToken;

/**
 * Stores, decrypts, refreshes and health-checks access tokens. Plaintext tokens exist only in memory, inside
 * an {@link AccountRef}, for the duration of an API call.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** Token status for the UI. {@code daysLeft} is null when the expiry is unknown. */
    public record TokenHealth(long accountId, String status, Instant expiresAt, Long daysLeft, Instant lastRefreshedAt,
                              boolean expiringSoon) {
    }

    private final TokenRepository tokens;
    private final AccountRepository accounts;
    private final TokenCipher cipher;
    private final MetaOAuthClient oauth;
    private final SyncProperties sync;
    private final Clock clock;

    public TokenService(TokenRepository tokens, AccountRepository accounts, TokenCipher cipher, MetaOAuthClient oauth,
                        SyncProperties sync, Clock clock) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.cipher = cipher;
        this.oauth = oauth;
        this.sync = sync;
        this.clock = clock;
    }

    public void store(long accountId, IssuedToken token, String scopes) {
        tokens.upsert(accountId, cipher.encrypt(accountId, token.accessToken()), TokenCipher.KEY_VERSION,
                token.tokenType(), scopes != null ? scopes : token.permissions(), token.expiresAt(), clock.instant());
    }

    /** API identity for an account, with the decrypted token. Throws if the account has no usable token. */
    public AccountRef ref(Account account) {
        if (!account.loginType().direct()) {
            throw new IllegalStateException("@" + account.username() + " is read-only (data from "
                    + (account.loginType() == LoginType.WINDSOR ? "Windsor.ai" : "a CSV import")
                    + "). Connect it with Instagram or Facebook Login to use this.");
        }
        StoredToken t = tokens.find(account.id())
                .orElseThrow(() -> new IllegalStateException("Account " + account.username() + " has no stored token"));
        if (TokenRepository.Status.INVALID.name().equals(t.status())) {
            throw new IllegalStateException("Token for " + account.username() + " is invalid; reconnect the account");
        }
        return new AccountRef(account.id(), account.igUserId(), account.loginType(),
                cipher.decrypt(account.id(), t.encryptedToken()));
    }

    /** Called when Meta rejects a token: the account needs reconnecting, and the UI and MCP show why. */
    public void markInvalid(long accountId, String reason) {
        tokens.markStatus(accountId, TokenRepository.Status.INVALID);
        accounts.markStatus(accountId, AccountRepository.Status.NEEDS_RECONNECT,
                "Instagram rejected the access token (" + reason + "). Reconnect the account.");
        log.warn("Account {} marked NEEDS_RECONNECT: {}", accountId, reason);
    }

    public TokenHealth health(long accountId) {
        return tokens.find(accountId).map(t -> health(t, clock.instant()))
                .orElse(new TokenHealth(accountId, "MISSING", null, null, null, false));
    }

    private TokenHealth health(StoredToken t, Instant now) {
        Long daysLeft = t.expiresAt() == null ? null : Duration.between(now, t.expiresAt()).toDays();
        boolean soon = daysLeft != null && daysLeft <= sync.tokenWarningDays();
        return new TokenHealth(t.accountId(), t.status(), t.expiresAt(), daysLeft, t.lastRefreshedAt(), soon);
    }

    /**
     * Daily check (plan section 5): refreshes Instagram Login tokens that are old enough, marks tokens that
     * expire within the warning window as EXPIRING, and expired ones as INVALID.
     */
    public void checkAll() {
        Instant now = clock.instant();
        for (Account account : accounts.findAll()) {
            tokens.find(account.id()).ifPresent(t -> check(account, t, now));
        }
    }

    private void check(Account account, StoredToken t, Instant now) {
        if (TokenRepository.Status.INVALID.name().equals(t.status())) {
            return;
        }
        if (t.expiresAt() != null && !t.expiresAt().isAfter(now)) {
            markInvalid(account.id(), "expired at " + t.expiresAt());
            return;
        }
        if (account.loginType() == LoginType.INSTAGRAM_LOGIN && refreshDue(t, now)) {
            try {
                String current = cipher.decrypt(account.id(), t.encryptedToken());
                IssuedToken refreshed = oauth.instagramRefresh(account.id(), current);
                store(account.id(), refreshed, t.scopes());
                log.info("Refreshed Instagram token for {}; new expiry {}", account.username(), refreshed.expiresAt());
                return;
            } catch (GraphApiException e) {
                if (e.kind() == GraphApiException.Kind.TOKEN_INVALID) {
                    markInvalid(account.id(), e.getMessage());
                    return;
                }
                log.warn("Token refresh failed for {} (will retry next check): {}", account.username(), e.getMessage());
            }
        }
        TokenHealth h = health(t, now);
        if (h.expiringSoon()) {
            tokens.markStatus(account.id(), TokenRepository.Status.EXPIRING);
            log.warn("Token for {} expires in {} day(s) ({}); {}", account.username(), h.daysLeft(), t.expiresAt(),
                    account.loginType() == LoginType.FACEBOOK_LOGIN
                            ? "reconnect via Facebook Login to renew it" : "automatic refresh has not succeeded yet");
        }
    }

    /** Meta allows refreshing once the token is ≥ 24 h old; PageLens refreshes after the configured age. */
    private boolean refreshDue(StoredToken t, Instant now) {
        Instant issued = t.lastRefreshedAt() != null ? t.lastRefreshedAt() : t.createdAt();
        Duration age = Duration.between(issued, now);
        return age.compareTo(Duration.ofDays(Math.max(1, sync.tokenRefreshAfterDays()))) >= 0;
    }
}
