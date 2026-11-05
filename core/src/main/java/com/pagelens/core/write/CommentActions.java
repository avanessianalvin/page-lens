package com.pagelens.core.write;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pagelens.core.auth.TokenService;
import com.pagelens.core.instagram.GraphApiException;
import com.pagelens.core.instagram.IgModels.AccountRef;
import com.pagelens.core.instagram.InstagramWriteApi;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.CommentRepository;
import com.pagelens.core.repo.CommentRepository.Target;

/**
 * Reply to, hide/unhide and delete comments (plan section 2). Every call, including dry runs and refusals, is
 * audited. Comment text never goes into logs or the audit trail: only its length.
 */
@Service
public class CommentActions {

    /** PageLens's cap; Meta documents no reply limit, so we use the caption limit. */
    public static final int MAX_REPLY_LENGTH = 2200;

    private static final Logger log = LoggerFactory.getLogger(CommentActions.class);

    private final CommentRepository comments;
    private final AccountRepository accounts;
    private final TokenService tokens;
    private final InstagramWriteApi api;
    private final WriteGuard guard;
    private final AuditLogRepository audit;
    private final Clock clock;

    public CommentActions(CommentRepository comments, AccountRepository accounts, TokenService tokens,
                          InstagramWriteApi api, WriteGuard guard, AuditLogRepository audit, Clock clock) {
        this.comments = comments;
        this.accounts = accounts;
        this.tokens = tokens;
        this.api = api;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
    }

    public WriteResult reply(long commentId, String message, boolean dryRun, Actor actor) {
        WriteResult.Builder r = new WriteResult.Builder(dryRun);
        String text = message == null ? "" : message.strip();
        Target t = target(commentId, r);
        if (text.isEmpty()) {
            r.error("The reply is empty");
        } else if (text.codePointCount(0, text.length()) > MAX_REPLY_LENGTH) {
            r.error("The reply is longer than " + MAX_REPLY_LENGTH + " characters");
        }
        if (t != null) {
            if (t.hidden() && t.parentId() == null) {
                r.error("Instagram does not allow replies to hidden comments; unhide it first");
            }
            if (t.parentId() != null) {
                r.warn("This is a reply; Instagram attaches replies to the top-level comment of the thread");
            }
            r.detail("replyTo", "@" + t.username()).detail("post", t.igMediaId());
        }
        r.detail("length", text.codePointCount(0, text.length()));
        return run("comment.reply", t, r, actor, dryRun, ref -> {
            String parentIgId = t.parentId() == null ? t.igCommentId() : t.parentIgCommentId();
            long parentLocalId = t.parentId() == null ? t.id() : t.parentId();
            String newId = api.reply(ref, parentIgId, text);
            if (newId != null) {
                comments.insertOwnReply(t.mediaId(), parentLocalId, newId, t.accountUsername(), text, clock.instant());
            }
            r.detail("newCommentId", newId);
        });
    }

    public WriteResult setHidden(long commentId, boolean hidden, boolean dryRun, Actor actor) {
        WriteResult.Builder r = new WriteResult.Builder(dryRun);
        Target t = target(commentId, r);
        if (t != null) {
            if (t.hidden() == hidden) {
                r.warn("The comment is already " + (hidden ? "hidden" : "visible"));
            }
            if (hidden && t.username() != null && t.username().equalsIgnoreCase(t.accountUsername())) {
                r.warn("Instagram always shows the account owner's own comments, even when hidden");
            }
            r.detail("author", "@" + t.username()).detail("post", t.igMediaId());
        }
        return run(hidden ? "comment.hide" : "comment.unhide", t, r, actor, dryRun, ref -> {
            api.setHidden(ref, t.igCommentId(), hidden);
            comments.setHidden(t.id(), hidden);
        });
    }

    public WriteResult delete(long commentId, boolean dryRun, Actor actor) {
        WriteResult.Builder r = new WriteResult.Builder(dryRun);
        Target t = target(commentId, r);
        if (t != null) {
            r.detail("author", "@" + t.username()).detail("post", t.igMediaId());
            r.warn("Deleting is permanent and also removes the comment's replies");
        }
        return run("comment.delete", t, r, actor, dryRun, ref -> {
            api.deleteComment(ref, t.igCommentId());
            comments.delete(t.id());
        });
    }

    // ------------------------------------------------------------------------------------------ plumbing

    private interface Action {
        void apply(AccountRef ref);
    }

    private Target target(long commentId, WriteResult.Builder r) {
        Target t = comments.findTarget(commentId).orElse(null);
        if (t == null) {
            r.error("No comment with id " + commentId + " (use the PageLens comment id)");
            return null;
        }
        Account a = accounts.findById(t.accountId()).orElseThrow();
        if (!"ACTIVE".equals(a.status())) {
            r.error("@" + a.username() + " needs reconnecting before it can moderate comments");
        }
        return t;
    }

    private WriteResult run(String action, Target t, WriteResult.Builder r, Actor actor, boolean dryRun, Action body) {
        String target = t == null ? "comment ?" : "@" + t.accountUsername() + " comment " + t.igCommentId();
        try {
            guard.check(dryRun, r);
        } catch (WriteGuard.WriteActionsDisabledException e) {
            audit.record(actor, action, target, summary(r), false, "disabled");
            throw e;
        }
        if (r.hasErrors() || dryRun) {
            WriteResult result = r.build();
            audit.record(actor, action, target, summary(r), dryRun, result.ok() ? "dry_run_ok" : "rejected");
            return result;
        }
        try {
            Account account = accounts.findById(t.accountId()).orElseThrow();
            body.apply(tokens.ref(account));
            audit.record(actor, action, target, summary(r), false, "ok");
            log.info("{} done on {}", action, target);
            return r.build();
        } catch (GraphApiException e) {
            if (e.kind() == GraphApiException.Kind.TOKEN_INVALID) {
                tokens.markInvalid(t.accountId(), e.getMessage());
            }
            r.error(explain(e));
            audit.record(actor, action, target, summary(r), false, "failed: " + e.errorCode());
            return r.build();
        }
    }

    static String explain(GraphApiException e) {
        return switch (e.kind()) {
            case PERMISSION -> "Instagram refused: the account's token lacks the needed permission. Reconnect the "
                    + "account to grant comment moderation and publishing. (" + e.getMessage() + ")";
            case TOKEN_INVALID -> "Instagram rejected the token; reconnect the account. (" + e.getMessage() + ")";
            case RATE_LIMITED -> "Instagram rate limit reached; try again later. (" + e.getMessage() + ")";
            default -> "Instagram error: " + e.getMessage();
        };
    }

    /** Audit summary: what/how much, never comment text. */
    private static String summary(WriteResult.Builder r) {
        WriteResult res = r.build();
        StringBuilder s = new StringBuilder();
        res.details().forEach((k, v) -> s.append(k).append('=').append(v).append(' '));
        if (!res.errors().isEmpty()) {
            s.append("errors=").append(res.errors().size());
        }
        return s.toString().trim();
    }
}
