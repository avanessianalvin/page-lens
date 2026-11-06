package com.pagelens.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.pagelens.core.analysis.AnalysisService;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.write.CommentActions;
import com.pagelens.core.write.PublishModels.Item;
import com.pagelens.core.write.PublishModels.ItemType;
import com.pagelens.core.write.PublishModels.Kind;
import com.pagelens.core.write.PublishModels.Options;
import com.pagelens.core.write.PublishModels.PublishJob;
import com.pagelens.core.write.PublishModels.PublishRequest;
import com.pagelens.core.write.PublishService;
import com.pagelens.core.write.WriteGuard;
import com.pagelens.core.write.WriteResult;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * MCP write tools (plan section 9: "marked as write actions"). Safety layers:
 * <ol>
 *   <li>registered only when both {@code PAGELENS_WRITE_ACTIONS_ENABLED} and {@code PAGELENS_MCP_WRITE_TOOLS} are on;</li>
 *   <li>{@code dry_run} defaults to <b>true</b>: Claude has to preview first and pass {@code dry_run=false} to act;</li>
 *   <li>annotated as non-read-only, so clients ask the user before running them;</li>
 *   <li>every call, dry or real, goes to the audit trail with actor {@code mcp}.</li>
 * </ol>
 * Publishing from Claude takes public URLs only; local video uploads are dashboard-only.
 */
@Component
public class PageLensWriteTools {

    private static final ToolAnnotations WRITE = new ToolAnnotations(null, false, false, false, true, null);
    private static final ToolAnnotations IDEMPOTENT_WRITE = new ToolAnnotations(null, false, false, true, true, null);

    private final PageLensTools tools;
    private final CommentActions comments;
    private final PublishService publisher;
    private final AnalysisService analysis;
    private final WriteGuard guard;
    private final McpProperties props;

    public PageLensWriteTools(PageLensTools tools, CommentActions comments, PublishService publisher,
                              AnalysisService analysis, WriteGuard guard, McpProperties props) {
        this.tools = tools;
        this.comments = comments;
        this.publisher = publisher;
        this.analysis = analysis;
        this.guard = guard;
        this.props = props;
    }

    public boolean enabled() {
        return props.writeToolsEnabled() && guard.enabled();
    }

    public List<SyncToolSpecification> specifications() {
        if (!enabled()) {
            return List.of();
        }
        String dryRun = "dry_run (default true) only validates and shows what would happen. Show that to the user, "
                + "and call again with dry_run=false only after they approve.";
        List<SyncToolSpecification> out = new ArrayList<>();

        out.add(tool("reply_to_comment", "Reply to a comment", WRITE,
                "Posts a public reply on Instagram to a comment on one of the owner's posts, as the account. "
                        + "comment_id is the PageLens commentId from get_post_comments. Replies to replies are "
                        + "attached to the thread's top-level comment; hidden comments can't be replied to. " + dryRun,
                new ToolArgs.Schema().prop("comment_id", "integer", "PageLens commentId from get_post_comments.")
                        .prop("message", "string", "The reply text (max 2200 characters).")
                        .prop("dry_run", "boolean", "Default true: validate only.")
                        .required("comment_id").required("message"),
                a -> result(comments.reply(a.requiredLong("comment_id"), a.str("message"), a.bool("dry_run", true),
                        Actor.MCP))));

        out.add(tool("hide_comment", "Hide or unhide a comment", IDEMPOTENT_WRITE,
                "Hides (hidden=true) or unhides (hidden=false) a comment on one of the owner's posts. Hidden "
                        + "comments stay visible to their author. The account's own comments can't be hidden. " + dryRun,
                new ToolArgs.Schema().prop("comment_id", "integer", "PageLens commentId from get_post_comments.")
                        .prop("hidden", "boolean", "true to hide (default), false to unhide.")
                        .prop("dry_run", "boolean", "Default true: validate only.")
                        .required("comment_id"),
                a -> result(comments.setHidden(a.requiredLong("comment_id"), a.bool("hidden", true),
                        a.bool("dry_run", true), Actor.MCP))));

        out.add(tool("publish_post", "Publish a post", WRITE,
                "Publishes a photo (IMAGE), Reel (REEL), story (STORY) or carousel (CAROUSEL, 2-20 items) to the "
                        + "owner's Instagram account. Media must be at PUBLIC URLs Instagram can download: JPEG "
                        + "images up to 8 MB; MP4/MOV videos. Caption up to 2200 characters, 30 hashtags, 20 "
                        + "@-mentions; stories have no caption. Posts can't be edited or deleted through the API "
                        + "afterwards. " + dryRun + " A real publish waits up to 60 s and then returns the job; "
                        + "videos may need longer, so check with get_publish_status.",
                new ToolArgs.Schema().account()
                        .enumProp("kind", List.of("IMAGE", "REEL", "STORY", "CAROUSEL"), "What to publish.")
                        .prop("caption", "string", "Caption text (not used for stories).")
                        .raw("media", Map.of("type", "array", "minItems", 1, "maxItems", 20,
                                "description", "Media in order. IMAGE/REEL/STORY take exactly one item.",
                                "items", Map.of("type", "object", "required", List.of("type", "url"),
                                        "additionalProperties", false,
                                        "properties", Map.of(
                                                "type", Map.of("type", "string", "enum", List.of("IMAGE", "VIDEO")),
                                                "url", Map.of("type", "string", "description", "Public https URL"))))
                        )
                        .prop("alt_text", "string", "Accessibility text (single-image posts only, max 1000).")
                        .prop("share_to_feed", "boolean", "Reels: also show in the profile grid (Instagram's default).")
                        .prop("cover_url", "string", "Reels: public JPEG cover image URL.")
                        .prop("thumb_offset_ms", "integer", "Reels: frame for the thumbnail, in milliseconds.")
                        .prop("idempotency_key", "string", "Optional. Reusing a key never publishes twice; "
                                + "by default identical content maps to the same key.")
                        .prop("dry_run", "boolean", "Default true: validate only (checks URLs and the 24 h limit).")
                        .required("kind").required("media"),
                this::publish));

        out.add(tool("get_publish_status", "Publish status", new ToolAnnotations(null, true, false, true, false, null),
                "Status of a publish job from publish_post: QUEUED, CREATING, PROCESSING, PUBLISHING, PUBLISHED "
                        + "(with permalink) or FAILED (with the reason).",
                new ToolArgs.Schema().prop("job_id", "integer", "jobId returned by publish_post.").required("job_id"),
                a -> job(publisher.job(a.requiredLong("job_id"))
                        .orElseThrow(() -> new IllegalArgumentException("No publish job " + a.str("job_id"))))));
        return out;
    }

    private Object publish(ToolArgs a) {
        Account acc = analysis.resolveAccount(a.str("account"));
        Kind kind;
        try {
            kind = Kind.valueOf(a.str("kind").toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("kind must be IMAGE, REEL, STORY or CAROUSEL");
        }
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> m : a.objects("media")) {
            Object type = m.get("type");
            Object url = m.get("url");
            ItemType t;
            try {
                t = ItemType.valueOf(String.valueOf(type).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("media type must be IMAGE or VIDEO");
            }
            items.add(new Item(t, url == null ? null : url.toString(), null)); // Claude: URLs only
        }
        Options options = new Options(a.str("share_to_feed") == null ? null : a.bool("share_to_feed", true),
                a.str("cover_url"), a.optionalInt("thumb_offset_ms"), a.str("alt_text"));
        PublishRequest req = new PublishRequest(acc.id(), kind, a.str("caption"), items, options, a.str("idempotency_key"));
        if (a.bool("dry_run", true)) {
            return result(publisher.validate(req, Actor.MCP));
        }
        WriteResult submitted = publisher.submit(req, Actor.MCP);
        if (!submitted.ok() || !(submitted.details().get("jobId") instanceof Long jobId)) {
            return result(submitted);
        }
        try {
            Map<String, Object> m = new LinkedHashMap<>(result(submitted));
            m.put("job", job(publisher.await(jobId, Duration.ofSeconds(60))));
            return m;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(submitted);
        }
    }

    private Map<String, Object> job(PublishJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", j.id());
        m.put("account", "@" + j.accountUsername());
        m.put("kind", j.kind());
        m.put("status", j.status());
        m.put("permalink", j.permalink());
        m.put("igMediaId", j.igMediaId());
        m.put("error", j.errorMessage());
        m.put("createdAt", tools.views().time(j.createdAt()));
        m.put("publishedAt", tools.views().time(j.publishedAt()));
        if (!j.finished()) {
            m.put("next", "Still running; call get_publish_status again in a minute.");
        }
        return m;
    }

    private static Map<String, Object> result(WriteResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dryRun", r.dryRun());
        m.put("ok", r.ok());
        if (!r.errors().isEmpty()) {
            m.put("errors", r.errors());
        }
        if (!r.warnings().isEmpty()) {
            m.put("warnings", r.warnings());
        }
        m.put("details", r.details());
        if (r.dryRun() && r.ok()) {
            m.put("next", "Validation passed. Ask the user to confirm, then call again with dry_run=false.");
        }
        return m;
    }

    private SyncToolSpecification tool(String name, String title, ToolAnnotations annotations, String description,
                                       ToolArgs.Schema schema, Function<ToolArgs, Object> handler) {
        Tool tool = Tool.builder(name, schema.build()).title(title).description(description)
                .annotations(annotations).build();
        return new SyncToolSpecification(tool, (exchange, req) -> tools.call(name, req.arguments(), handler));
    }
}
