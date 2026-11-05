package com.pagelens.web.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.pagelens.core.auth.AccountConnectionService;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.instagram.SecretMasker;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.sync.SyncService;

import jakarta.servlet.http.HttpSession;

/**
 * Meta redirects the browser here after the user approves access. The {@code state} must match the one stored
 * in this session (CSRF protection for the OAuth flow). The browser then lands back on the SPA's Accounts page.
 */
@Controller
public class OAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackController.class);

    private final AccountConnectionService connections;
    private final SyncService sync;

    public OAuthCallbackController(AccountConnectionService connections, SyncService sync) {
        this.connections = connections;
        this.sync = sync;
    }

    @GetMapping("/auth/callback")
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error,
                           @RequestParam(name = "error_description", required = false) String errorDescription,
                           HttpSession session) {
        String expected = (String) session.getAttribute(AccountController.STATE_ATTR);
        LoginType type = (LoginType) session.getAttribute(AccountController.LOGIN_TYPE_ATTR);
        session.removeAttribute(AccountController.STATE_ATTR);
        session.removeAttribute(AccountController.LOGIN_TYPE_ATTR);

        if (error != null) {
            return back("error", errorDescription != null ? errorDescription : error);
        }
        if (expected == null || type == null || state == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            log.warn("OAuth callback rejected: state missing or mismatched");
            return back("error", "The login link expired or did not come from this session. Start again from Accounts.");
        }
        if (code == null || code.isBlank()) {
            return back("error", "Meta did not return an authorization code");
        }
        try {
            List<Account> connected = connections.completeAuthorization(type, code);
            for (Account a : connected) {
                try {
                    sync.startAsync(a.id(), SyncService.JobType.FULL, SyncService.Trigger.UI);
                } catch (SyncService.AlreadyRunningException ignored) {
                    // a sync is already on its way
                }
            }
            return back("connected", String.valueOf(connected.size()));
        } catch (RuntimeException e) {
            log.warn("OAuth completion failed: {}", SecretMasker.mask(e.getMessage()));
            return back("error", SecretMasker.mask(e.getMessage()));
        }
    }

    private static String back(String param, String value) {
        return "redirect:/accounts?" + param + "=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
