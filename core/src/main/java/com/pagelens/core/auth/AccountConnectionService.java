package com.pagelens.core.auth;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pagelens.core.instagram.IgModels.AccountRef;
import com.pagelens.core.instagram.IgModels.IgProfile;
import com.pagelens.core.instagram.IgModels.IssuedToken;
import com.pagelens.core.instagram.IgModels.LinkedIgAccount;
import com.pagelens.core.instagram.InstagramApi;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.instagram.MetaOAuthClient;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;

/**
 * Connects and disconnects Instagram accounts. Two ways in for each login setup: the OAuth redirect flow,
 * or pasting a token generated in the Meta App Dashboard (the fallback if a localhost redirect is refused).
 */
@Service
public class AccountConnectionService {

    private static final Logger log = LoggerFactory.getLogger(AccountConnectionService.class);

    private final MetaOAuthClient oauth;
    private final InstagramApi api;
    private final TokenService tokens;
    private final AccountRepository accounts;
    private final AuditLogRepository audit;

    public AccountConnectionService(MetaOAuthClient oauth, InstagramApi api, TokenService tokens,
                                    AccountRepository accounts, AuditLogRepository audit) {
        this.oauth = oauth;
        this.api = api;
        this.tokens = tokens;
        this.accounts = accounts;
        this.audit = audit;
    }

    public String authorizationUrl(LoginType type, String state) {
        return oauth.authorizationUrl(type, state);
    }

    /** Completes the OAuth redirect: exchanges the code and stores every account it grants access to. */
    public List<Account> completeAuthorization(LoginType type, String code) {
        if (type == LoginType.INSTAGRAM_LOGIN) {
            IssuedToken token = oauth.instagramExchangeCode(code);
            return List.of(connectInstagram(token, String.join(",", MetaOAuthClient.INSTAGRAM_SCOPES), "oauth"));
        }
        IssuedToken userToken = oauth.facebookExchangeCode(code);
        return connectFacebook(userToken, "oauth");
    }

    /**
     * Connects using a token pasted from the App Dashboard. Instagram Login tokens are used as-is: dashboard
     * tokens are already long-lived, and the expiry becomes known at the first refresh. Facebook user tokens
     * are exchanged for a long-lived one first.
     */
    public List<Account> connectWithToken(LoginType type, String accessToken) {
        String token = accessToken == null ? "" : accessToken.trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Token is empty");
        }
        if (type == LoginType.INSTAGRAM_LOGIN) {
            return List.of(connectInstagram(new IssuedToken(token, "bearer", null, null, null), null, "pasted-token"));
        }
        return connectFacebook(oauth.facebookLongLived(token), "pasted-token");
    }

    /** Disconnect = delete the account and all its data (Meta platform terms; plan section 12). */
    public boolean disconnect(long accountId, Actor actor) {
        Account account = accounts.findById(accountId).orElse(null);
        if (account == null) {
            return false;
        }
        boolean deleted = accounts.delete(accountId);
        audit.record(actor, "account.disconnect", "@" + account.username(),
                "deleted account and all synced data", false, deleted ? "ok" : "not_found");
        log.info("Disconnected @{} and purged its data", account.username());
        return deleted;
    }

    private Account connectInstagram(IssuedToken token, String scopes, String via) {
        IgProfile profile = api.profile(new AccountRef(null, null, LoginType.INSTAGRAM_LOGIN, token.accessToken()));
        if (profile.igUserId() == null) {
            throw new IllegalStateException("Instagram did not return the account's user_id");
        }
        long id = accounts.upsertConnected(profile.igUserId(), LoginType.INSTAGRAM_LOGIN, null, profile.username());
        accounts.updateProfile(id, profile);
        tokens.store(id, token, scopes);
        audit.record(Actor.UI, "account.connect", "@" + profile.username(), "instagram_login via " + via, false, "ok");
        return accounts.findById(id).orElseThrow();
    }

    private List<Account> connectFacebook(IssuedToken userToken, String via) {
        List<LinkedIgAccount> linked = oauth.facebookLinkedAccounts(userToken.accessToken());
        if (linked.isEmpty()) {
            throw new IllegalStateException("No Instagram professional account is linked to a Facebook Page you manage. "
                    + "Link the account to a Page, or connect it with Instagram Login instead.");
        }
        List<Account> out = new ArrayList<>();
        String scopes = String.join(",", MetaOAuthClient.FACEBOOK_SCOPES);
        for (LinkedIgAccount l : linked) {
            long id = accounts.upsertConnected(l.igUserId(), LoginType.FACEBOOK_LOGIN, l.pageId(), l.igUsername());
            tokens.store(id, userToken, scopes);
            try {
                accounts.updateProfile(id, api.profile(new AccountRef(id, l.igUserId(), LoginType.FACEBOOK_LOGIN,
                        userToken.accessToken())));
            } catch (RuntimeException e) {
                log.warn("Connected @{} but could not load its profile yet: {}", l.igUsername(), e.getMessage());
            }
            audit.record(Actor.UI, "account.connect", "@" + l.igUsername(),
                    "facebook_login via " + via + " (page " + l.pageId() + ")", false, "ok");
            out.add(accounts.findById(id).orElseThrow());
        }
        return out;
    }
}
