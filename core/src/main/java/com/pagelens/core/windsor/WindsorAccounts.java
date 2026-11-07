package com.pagelens.core.windsor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pagelens.core.instagram.IgModels.IgProfile;
import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;

import tools.jackson.databind.JsonNode;

/**
 * Finds the Instagram accounts connected in the owner's Windsor.ai workspace and adds them to PageLens as
 * {@code WINDSOR} accounts. Accounts already connected directly through Meta are left as they are.
 */
@Service
public class WindsorAccounts {

    /** One account Windsor returned and what PageLens did with it. */
    public record Found(long accountId, String igUserId, String username, LoginType loginType, boolean added) {
    }

    private static final Logger log = LoggerFactory.getLogger(WindsorAccounts.class);

    private final WindsorClient client;
    private final AccountRepository accounts;
    private final AuditLogRepository audit;
    private final Clock clock;

    public WindsorAccounts(WindsorClient client, AccountRepository accounts, AuditLogRepository audit, Clock clock) {
        this.client = client;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean configured() {
        return client.configured();
    }

    public List<Found> discover(Actor actor) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode r : client.rows(null, WindsorMapper.PROFILE_FIELDS, today.minusDays(1), today)) {
            String id = WindsorMapper.text(r, "account_id");
            if (id != null) {
                byId.put(id, r);
            }
        }
        List<Found> out = new ArrayList<>();
        for (JsonNode r : byId.values()) {
            IgProfile p = WindsorMapper.profile(r);
            boolean existed = accounts.findByIgUserId(p.igUserId()).isPresent();
            long id = accounts.upsertReadOnlySource(p.igUserId(), LoginType.WINDSOR, p.username());
            Account a = accounts.findById(id).orElseThrow();
            if (a.loginType() == LoginType.WINDSOR) {
                accounts.updateProfile(id, p);
            }
            out.add(new Found(id, p.igUserId(), a.username(), a.loginType(), !existed));
        }
        audit.record(actor, "windsor_discover", null, out.size() + " account(s)", false, "ok");
        log.info("Windsor.ai returned {} Instagram account(s)", out.size());
        return out;
    }
}
