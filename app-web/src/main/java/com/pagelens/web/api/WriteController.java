package com.pagelens.web.api;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pagelens.core.instagram.InstagramWriteApi.PublishQuota;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.write.CommentActions;
import com.pagelens.core.write.PublishModels.Item;
import com.pagelens.core.write.PublishModels.Kind;
import com.pagelens.core.write.PublishModels.Options;
import com.pagelens.core.write.PublishModels.PublishJob;
import com.pagelens.core.write.PublishModels.PublishRequest;
import com.pagelens.core.write.PublishService;
import com.pagelens.core.write.UploadStore;
import com.pagelens.core.write.WriteGuard;
import com.pagelens.core.write.WriteResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Write actions (plan section 8): comment moderation and publishing. All of them are off unless
 * {@code PAGELENS_WRITE_ACTIONS_ENABLED=true} (403 otherwise). {@code dryRun=true} validates without changing
 * anything. Every call is audited. Rejections come back as 422 with the reasons in {@code errors}.
 */
@RestController
@RequestMapping("/api")
public class WriteController {

    // Optional flags are Boolean, not boolean: Jackson 3 rejects a missing JSON field for a primitive.

    public record ReplyRequest(String message, Boolean dryRun) {
        boolean dry() {
            return Boolean.TRUE.equals(dryRun);
        }

        @Override
        public String toString() {
            return "ReplyRequest[dryRun=" + dryRun + "]"; // never log comment text
        }
    }

    public record HideRequest(Boolean hidden, Boolean dryRun) {
        boolean dry() {
            return Boolean.TRUE.equals(dryRun);
        }
    }

    public record PublishBody(@NotNull Long accountId, @NotNull Kind kind, String caption, List<Item> items,
                              Options options, Boolean dryRun, String idempotencyKey) {
        boolean dry() {
            return Boolean.TRUE.equals(dryRun);
        }
    }

    public record WriteStatus(boolean writeActionsEnabled, String howToEnable) {
    }

    private final CommentActions comments;
    private final PublishService publisher;
    private final UploadStore uploads;
    private final WriteGuard guard;

    public WriteController(CommentActions comments, PublishService publisher, UploadStore uploads, WriteGuard guard) {
        this.comments = comments;
        this.publisher = publisher;
        this.uploads = uploads;
        this.guard = guard;
    }

    @GetMapping("/write-status")
    public WriteStatus status() {
        return new WriteStatus(guard.enabled(), guard.enabled() ? null : WriteGuard.DISABLED_MESSAGE);
    }

    // ------------------------------------------------------------------------------------------ comments

    @PostMapping("/comments/{id}/reply")
    public ResponseEntity<WriteResult> reply(@PathVariable long id, @RequestBody ReplyRequest req) {
        return respond(comments.reply(id, req.message(), req.dry(), Actor.UI));
    }

    @PostMapping("/comments/{id}/hide")
    public ResponseEntity<WriteResult> hide(@PathVariable long id, @RequestBody HideRequest req) {
        return respond(comments.setHidden(id, !Boolean.FALSE.equals(req.hidden()), req.dry(), Actor.UI));
    }

    @DeleteMapping("/comments/{id}")
    public ResponseEntity<WriteResult> delete(@PathVariable long id, @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(comments.delete(id, dryRun, Actor.UI));
    }

    // ------------------------------------------------------------------------------------------ publishing

    /** Validates (dryRun=true) or queues a post. Returns 202 with {@code details.jobId} when queued. */
    @PostMapping("/publish")
    public ResponseEntity<WriteResult> publish(@Valid @RequestBody PublishBody body) {
        PublishRequest req = new PublishRequest(body.accountId(), body.kind(), body.caption(),
                body.items() == null ? List.of() : body.items(), body.options(),
                body.idempotencyKey() == null || body.idempotencyKey().isBlank() ? UUID.randomUUID().toString()
                        : body.idempotencyKey());
        if (body.dry()) {
            return respond(publisher.validate(req, Actor.UI));
        }
        WriteResult r = publisher.submit(req, Actor.UI);
        return r.ok() ? ResponseEntity.status(HttpStatus.ACCEPTED).body(r) : respond(r);
    }

    @GetMapping("/publish/{id}/status")
    public PublishJob job(@PathVariable long id) {
        return publisher.job(id).orElseThrow(() -> new NotFoundException("Publish job " + id));
    }

    @PostMapping("/publish/{id}/retry")
    public ResponseEntity<WriteResult> retry(@PathVariable long id) {
        WriteResult r = publisher.retry(id, Actor.UI);
        return r.ok() ? ResponseEntity.status(HttpStatus.ACCEPTED).body(r) : respond(r);
    }

    @GetMapping("/publish")
    public List<PublishJob> jobs(@RequestParam(required = false) Long accountId,
                                 @RequestParam(defaultValue = "20") int limit) {
        return publisher.recent(accountId, limit);
    }

    /** Instagram's 24 h publishing quota for an account (one read call to Instagram). */
    @GetMapping("/publish/quota")
    public PublishQuota quota(@RequestParam long accountId) {
        return publisher.quota(accountId);
    }

    /** Stages a local MP4/MOV video for a Reel, story or carousel item (Facebook Login accounts). */
    @PostMapping(value = "/publish/uploads", consumes = "multipart/form-data")
    public UploadStore.Upload upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (!guard.enabled()) {
            throw new WriteGuard.WriteActionsDisabledException();
        }
        try (var in = file.getInputStream()) {
            return uploads.save(in, file.getOriginalFilename());
        }
    }

    private static ResponseEntity<WriteResult> respond(WriteResult r) {
        return ResponseEntity.status(r.ok() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT).body(r);
    }
}
