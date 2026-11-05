package com.pagelens.web.api;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pagelens.core.auth.AccountConnectionService;
import com.pagelens.core.auth.TokenService;
import com.pagelens.core.auth.TokenService.TokenHealth;
import com.pagelens.core.config.MetaProperties;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.sync.SyncService;
import com.pagelens.core.sync.SyncService.JobType;
import com.pagelens.core.sync.SyncService.Trigger;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    static final String STATE_ATTR = "pagelens.oauth.state";
    static final String LOGIN_TYPE_ATTR = "pagelens.oauth.loginType";

    public record AccountView(Account account, TokenHealth token) {
    }

    public record ConnectOptions(boolean instagramLogin, boolean facebookLogin, String redirectUri) {
    }

    public record ConnectRequest(@NotNull LoginType loginType) {
    }

    public record ConnectResponse(String authorizationUrl) {
    }

    /** {@code accessToken} is never logged or echoed back. */
    public record TokenConnectRequest(@NotNull LoginType loginType, @NotBlank String accessToken) {
        @Override
        public String toString() {
            return "TokenConnectRequest[loginType=" + loginType + "]";
        }
    }

    public record SyncStarted(long runId) {
    }

    private final AccountRepository accounts;
    private final TokenService tokens;
    private final AccountConnectionService connections;
    private final SyncService sync;
    private final MetaProperties meta;
    private final SecureRandom random = new SecureRandom();

    public AccountController(AccountRepository accounts, TokenService tokens, AccountConnectionService connections,
                             SyncService sync, MetaProperties meta) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.connections = connections;
        this.sync = sync;
        this.meta = meta;
    }

    @GetMapping
    public List<AccountView> list() {
        return accounts.findAll().stream().map(a -> new AccountView(a, tokens.health(a.id()))).toList();
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable long id) {
        Account a = accounts.findById(id).orElseThrow(() -> new NotFoundException("Account " + id));
        return new AccountView(a, tokens.health(id));
    }

    @GetMapping("/connect-options")
    public ConnectOptions connectOptions() {
        return new ConnectOptions(meta.instagramLoginConfigured(), meta.facebookLoginConfigured(), meta.redirectUri());
    }

    /** Starts the OAuth redirect flow. The browser goes to the returned URL; Meta calls back /auth/callback. */
    @PostMapping("/connect")
    public ConnectResponse connect(@Valid @RequestBody ConnectRequest req, HttpSession session) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(STATE_ATTR, state);
        session.setAttribute(LOGIN_TYPE_ATTR, req.loginType());
        return new ConnectResponse(connections.authorizationUrl(req.loginType(), state));
    }

    /** Fallback connect path: a token generated in the Meta App Dashboard. */
    @PostMapping("/connect-token")
    public List<AccountView> connectWithToken(@Valid @RequestBody TokenConnectRequest req) {
        return connections.connectWithToken(req.loginType(), req.accessToken()).stream()
                .map(a -> new AccountView(a, tokens.health(a.id()))).toList();
    }

    /** Disconnects the account and deletes all of its data. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable long id) {
        if (!connections.disconnect(id, Actor.UI)) {
            throw new NotFoundException("Account " + id);
        }
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<SyncStarted> syncNow(@PathVariable long id,
                                               @RequestParam(defaultValue = "FULL") JobType job) {
        accounts.findById(id).orElseThrow(() -> new NotFoundException("Account " + id));
        return ResponseEntity.accepted().body(new SyncStarted(sync.startAsync(id, job, Trigger.UI)));
    }
}
