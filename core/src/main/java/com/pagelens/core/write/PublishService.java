package com.pagelens.core.write;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import com.pagelens.core.auth.TokenService;
import com.pagelens.core.config.PublishProperties;
import com.pagelens.core.instagram.GraphApiException;
import com.pagelens.core.instagram.GraphJson;
import com.pagelens.core.instagram.IgModels.AccountRef;
import com.pagelens.core.instagram.InstagramApi;
import com.pagelens.core.instagram.InstagramWriteApi;
import com.pagelens.core.instagram.InstagramWriteApi.ContainerState;
import com.pagelens.core.instagram.InstagramWriteApi.ContainerStatus;
import com.pagelens.core.logging.CorrelationId;
import com.pagelens.core.repo.AccountRepository;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.MediaRepository;
import com.pagelens.core.repo.PublishJobRepository;
import com.pagelens.core.repo.PublishJobRepository.JobRow;
import com.pagelens.core.repo.TokenRepository;
import com.pagelens.core.write.PublishModels.Item;
import com.pagelens.core.write.PublishModels.ItemType;
import com.pagelens.core.write.PublishModels.JobStatus;
import com.pagelens.core.write.PublishModels.Kind;
import com.pagelens.core.write.PublishModels.Options;
import com.pagelens.core.write.PublishModels.PublishJob;
import com.pagelens.core.write.PublishModels.PublishRequest;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishing via Meta's container workflow: create container(s) → wait for FINISHED → media_publish.
 *
 * <p><b>Never double-publish</b> (plan section 12):
 * <ul>
 *   <li>one job per idempotency key;</li>
 *   <li>a job is claimed with an atomic status transition, so it can't run twice at once;</li>
 *   <li>container ids are stored the moment Meta returns them, and a retry reuses the container;</li>
 *   <li>before and after {@code media_publish} the container's status is checked: {@code PUBLISHED} means done;</li>
 *   <li>write calls are never retried automatically.</li>
 * </ul>
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);
    private static final Duration STALE = Duration.ofMinutes(20);

    private final AccountRepository accounts;
    private final TokenRepository tokenRows;
    private final TokenService tokens;
    private final PublishJobRepository jobs;
    private final PublishValidator validator;
    private final InstagramWriteApi api;
    private final MediaRepository media;
    private final UploadStore uploads;
    private final WriteGuard guard;
    private final AuditLogRepository audit;
    private final PublishProperties props;
    private final JsonMapper json;
    private final TaskExecutor executor;
    private final Clock clock;

    public PublishService(AccountRepository accounts, TokenRepository tokenRows, TokenService tokens,
                          PublishJobRepository jobs, PublishValidator validator, InstagramWriteApi api,
                          MediaRepository media, UploadStore uploads, WriteGuard guard, AuditLogRepository audit,
                          PublishProperties props, GraphJson json,
                          @Qualifier("applicationTaskExecutor") TaskExecutor executor, Clock clock) {
        this.accounts = accounts;
        this.tokenRows = tokenRows;
        this.tokens = tokens;
        this.jobs = jobs;
        this.validator = validator;
        this.api = api;
        this.media = media;
        this.uploads = uploads;
        this.guard = guard;
        this.audit = audit;
        this.props = props;
        this.json = json.mapper();
        this.executor = executor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ entry points

    /** Dry run: validates everything (incl. media URLs and the 24 h quota) without creating anything on Instagram. */
    public WriteResult validate(PublishRequest req, Actor actor) {
        WriteResult.Builder r = new WriteResult.Builder(true);
        Account account = check(req, r);
        WriteResult result = r.build();
        audit.record(actor, "publish.validate", target(account), summary(req, result), true,
                result.ok() ? "dry_run_ok" : "rejected");
        return result;
    }

    /**
     * Validates, then queues and starts the job in the background. Returns at once with {@code jobId}. The same
     * idempotency key returns the existing job instead of publishing again.
     */
    public WriteResult submit(PublishRequest req, Actor actor) {
        WriteResult.Builder r = new WriteResult.Builder(false);
        Account account;
        try {
            guard.check(false, r);
        } catch (WriteGuard.WriteActionsDisabledException e) {
            audit.record(actor, "publish.submit", "account " + req.accountId(), summary(req, null), false, "disabled");
            throw e;
        }
        account = check(req, r);
        if (r.hasErrors()) {
            WriteResult result = r.build();
            audit.record(actor, "publish.submit", target(account), summary(req, result), false, "rejected");
            return result;
        }
        String key = req.idempotencyKey() == null || req.idempotencyKey().isBlank() ? contentKey(req) : req.idempotencyKey();
        String caption = req.kind() == Kind.STORY ? null : blankToNull(req.caption());
        long jobId = jobs.insertOrGet(req.accountId(), key, req.kind(), caption, json.writeValueAsString(req.items()),
                req.items().size(), json.writeValueAsString(req.options() == null ? Options.NONE : req.options()),
                actor.name().toLowerCase(), CorrelationId.current());
        PublishJob job = jobs.findJob(jobId).orElseThrow();
        r.detail("jobId", jobId).detail("status", job.status());
        if (job.status() != JobStatus.QUEUED) {
            r.warn("This exact post was already submitted (job " + jobId + ", " + job.status()
                    + "); it was not published again");
            audit.record(actor, "publish.submit", target(account), "job " + jobId + " (duplicate)", false, "duplicate");
            return r.build();
        }
        audit.record(actor, "publish.submit", target(account), summary(req, null) + " job=" + jobId, false, "queued");
        start(jobId);
        return r.build();
    }

    /** Re-runs a FAILED job, reusing its container when it is still usable. */
    public WriteResult retry(long jobId, Actor actor) {
        WriteResult.Builder r = new WriteResult.Builder(false);
        guard.check(false, r);
        PublishJob job = jobs.findJob(jobId).orElseThrow(() -> new IllegalArgumentException("No publish job " + jobId));
        if (job.status() != JobStatus.FAILED) {
            r.error("Only failed jobs can be retried (job is " + job.status() + ")");
            return r.build();
        }
        if (!jobs.claim(jobId, List.of(JobStatus.FAILED), JobStatus.QUEUED)) {
            r.error("The job changed state; reload and try again");
            return r.build();
        }
        audit.record(actor, "publish.retry", "@" + job.accountUsername(), "job " + jobId, false, "queued");
        start(jobId);
        return r.detail("jobId", jobId).detail("status", JobStatus.QUEUED).build();
    }

    public Optional<PublishJob> job(long id) {
        return jobs.findJob(id);
    }

    public List<PublishJob> recent(Long accountId, int limit) {
        return jobs.recent(accountId, limit);
    }

    /** Waits until the job is PUBLISHED or FAILED, or {@code max} passes; returns its latest state. */
    public PublishJob await(long jobId, Duration max) throws InterruptedException {
        Instant deadline = clock.instant().plus(max);
        PublishJob job = jobs.findJob(jobId).orElseThrow();
        while (!job.finished() && clock.instant().isBefore(deadline)) {
            Thread.sleep(500);
            job = jobs.findJob(jobId).orElseThrow();
        }
        return job;
    }

    public InstagramWriteApi.PublishQuota quota(long accountId) {
        Account a = accounts.findById(accountId).orElseThrow(() -> new IllegalArgumentException("Unknown account"));
        return api.quota(tokens.ref(a));
    }

    /**
     * Jobs a stopped process left mid-flight. If Instagram says the container was published, the job is
     * completed. Otherwise it is marked FAILED for the owner to retry. It is never re-published automatically.
     */
    public int recoverStale() {
        int n = 0;
        for (JobRow row : jobs.stale(clock.instant().minus(STALE))) {
            PublishJob job = row.job();
            try {
                if (job.containerId() != null) {
                    AccountRef ref = tokens.ref(accounts.findById(job.accountId()).orElseThrow());
                    if (api.containerState(ref, job.containerId()).status() == ContainerStatus.PUBLISHED) {
                        jobs.published(job.id(), null, null, clock.instant());
                        n++;
                        continue;
                    }
                }
                jobs.failed(job.id(), "Interrupted (PageLens stopped mid-publish). Check Instagram, then use Retry: "
                        + "it reuses the existing container and never posts twice.");
                n++;
            } catch (RuntimeException e) {
                log.warn("Could not recover publish job {}: {}", job.id(), e.getMessage());
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------ validation

    private Account check(PublishRequest req, WriteResult.Builder r) {
        Account account = accounts.findById(req.accountId()).orElse(null);
        if (account == null) {
            r.error("Unknown account " + req.accountId());
            return null;
        }
        if (r.dryRun()) {
            guard.check(true, r); // records "disabled" as a blocking error; real submits were guarded already
        }
        String scopes = tokenRows.find(account.id()).map(TokenRepository.StoredToken::scopes).orElse(null);
        validator.validate(req, account, scopes, r);
        if (!r.hasErrors()) {
            try {
                var q = api.quota(tokens.ref(account));
                r.detail("quotaUsed", q.used()).detail("quotaTotal", q.total());
                if (q.used() != null && q.total() != null && q.used() >= q.total()) {
                    r.error("Instagram's limit of " + q.total() + " published posts per 24 hours is reached");
                }
            } catch (GraphApiException e) {
                r.warn("Couldn't check the 24-hour publishing limit (" + e.getMessage() + ")");
            } catch (IllegalStateException e) {
                r.error(e.getMessage());
            }
        }
        return account;
    }

    // ------------------------------------------------------------------------------------------ execution

    private void start(long jobId) {
        String cid = CorrelationId.current() != null ? CorrelationId.current() : "publish-" + jobId;
        executor.execute(() -> {
            try (var ignored = CorrelationId.use(cid)) {
                execute(jobId);
            }
        });
    }

    void execute(long jobId) {
        if (!jobs.claim(jobId, List.of(JobStatus.QUEUED), JobStatus.CREATING)) {
            return; // someone else is running it
        }
        JobRow row = jobs.find(jobId).orElseThrow();
        PublishJob job = row.job();
        Account account = accounts.findById(job.accountId()).orElseThrow();
        Actor actor = "mcp".equals(job.actor()) ? Actor.MCP : Actor.UI;
        List<Item> items = json.readValue(row.itemsJson(), new TypeReference<List<Item>>() { });
        Options options = row.optionsJson() == null ? Options.NONE : json.readValue(row.optionsJson(), Options.class);
        try {
            AccountRef ref = tokens.ref(account);
            String containerId = job.containerId();
            if (containerId != null) {
                ContainerState s = api.containerState(ref, containerId);
                if (s.status() == ContainerStatus.PUBLISHED) {
                    finish(job, ref, null, items, actor);
                    return;
                }
                if (s.status() == ContainerStatus.EXPIRED || s.status() == ContainerStatus.ERROR) {
                    log.info("Container {} for job {} is {}; creating a new one", containerId, jobId, s.status());
                    containerId = null;
                }
            }
            if (containerId == null) {
                containerId = createContainers(job, ref, items, options);
            }
            jobs.status(jobId, JobStatus.PROCESSING);
            boolean video = items.stream().anyMatch(i -> i.type() == ItemType.VIDEO);
            awaitFinished(ref, containerId, video ? props.videoTimeout() : props.imageTimeout());

            jobs.status(jobId, JobStatus.PUBLISHING);
            if (api.containerState(ref, containerId).status() == ContainerStatus.PUBLISHED) {
                finish(job, ref, null, items, actor);
                return;
            }
            String mediaId;
            try {
                mediaId = api.publish(ref, containerId);
            } catch (GraphApiException e) {
                // The response may be lost while the publish succeeded: Instagram's word decides.
                if (api.containerState(ref, containerId).status() == ContainerStatus.PUBLISHED) {
                    finish(job, ref, null, items, actor);
                    return;
                }
                throw e;
            }
            finish(job, ref, mediaId, items, actor);
        } catch (GraphApiException e) {
            if (e.kind() == GraphApiException.Kind.TOKEN_INVALID) {
                tokens.markInvalid(account.id(), e.getMessage());
            }
            fail(job, CommentActions.explain(e), actor);
        } catch (RuntimeException e) {
            log.error("Publish job {} failed", jobId, e);
            fail(job, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), actor);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, "Interrupted", actor);
        }
    }

    private String createContainers(PublishJob job, AccountRef ref, List<Item> items, Options o)
            throws InterruptedException {
        if (job.kind() == Kind.CAROUSEL) {
            List<String> children = new ArrayList<>();
            for (Item item : items) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("is_carousel_item", "true");
                String child = create(job, ref, item, p, "VIDEO");
                children.add(child);
                jobs.children(job.id(), children);
                if (item.type() == ItemType.VIDEO) {
                    awaitFinished(ref, child, props.videoTimeout()); // children must be ready before the parent
                }
            }
            Map<String, String> parent = new LinkedHashMap<>();
            parent.put("media_type", "CAROUSEL");
            parent.put("children", String.join(",", children));
            putIfPresent(parent, "caption", job.caption());
            String id = api.createContainer(ref, parent).id();
            jobs.container(job.id(), id);
            return id;
        }
        Item item = items.getFirst();
        Map<String, String> p = new LinkedHashMap<>();
        String videoType = switch (job.kind()) {
            case REEL -> "REELS";
            case STORY -> "STORIES";
            default -> "VIDEO";
        };
        if (job.kind() == Kind.STORY && item.type() == ItemType.IMAGE) {
            p.put("media_type", "STORIES");
        }
        if (job.kind() != Kind.STORY) {
            putIfPresent(p, "caption", job.caption());
        }
        if (job.kind() == Kind.IMAGE) {
            putIfPresent(p, "alt_text", o.altText());
        }
        if (job.kind() == Kind.REEL) {
            if (o.shareToFeed() != null) {
                p.put("share_to_feed", o.shareToFeed().toString());
            }
            putIfPresent(p, "cover_url", o.coverUrl());
            if (o.thumbOffsetMs() != null) {
                p.put("thumb_offset", o.thumbOffsetMs().toString());
            }
        }
        String id = create(job, ref, item, p, videoType);
        jobs.container(job.id(), id);
        return id;
    }

    /** Creates one container for one item (URL or resumable upload) and returns its id. */
    private String create(PublishJob job, AccountRef ref, Item item, Map<String, String> params, String videoMediaType) {
        if (item.type() == ItemType.IMAGE) {
            params.put("image_url", item.url().trim());
            return api.createContainer(ref, params).id();
        }
        params.put("media_type", videoMediaType);
        if (item.uploadId() == null || item.uploadId().isBlank()) {
            params.put("video_url", item.url().trim());
            return api.createContainer(ref, params).id();
        }
        Path file = uploads.resolve(item.uploadId())
                .orElseThrow(() -> new IllegalStateException("The uploaded video is no longer available; upload it again"));
        params.put("upload_type", "resumable");
        InstagramWriteApi.Container c = api.createContainer(ref, params);
        if (c.uploadUri() == null) {
            throw new IllegalStateException("Instagram did not return an upload address for the video");
        }
        long size;
        try {
            size = Files.size(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try {
            api.uploadVideo(ref, c.uploadUri(), file, size, 0);
        } catch (GraphApiException e) {
            long done = api.uploadedBytes(ref, c.id());
            if (done <= 0 || done >= size) {
                throw e;
            }
            log.info("Resuming upload for job {} at byte {}", job.id(), done);
            api.uploadVideo(ref, c.uploadUri(), file, size, done);
        }
        return c.id();
    }

    private void awaitFinished(AccountRef ref, String containerId, Duration timeout) throws InterruptedException {
        Instant deadline = clock.instant().plus(timeout);
        Duration wait = props.pollInitial();
        while (true) {
            ContainerState s = api.containerState(ref, containerId);
            switch (s.status()) {
                case FINISHED, PUBLISHED -> {
                    return;
                }
                case ERROR -> throw new IllegalStateException("Instagram could not process the media"
                        + (s.detail() == null ? "" : " (" + s.detail() + ")")
                        + ". Check the format: JPEG images; MP4/MOV H.264 + AAC video.");
                case EXPIRED -> throw new IllegalStateException("The container expired before publishing; submit again");
                default -> { }
            }
            if (!clock.instant().plus(wait).isBefore(deadline)) {
                throw new IllegalStateException("Instagram is still processing the media after " + timeout.toMinutes()
                        + " min; use Retry later (the container is kept)");
            }
            Thread.sleep(wait.toMillis());
            wait = wait.multipliedBy(2).compareTo(props.pollMax()) > 0 ? props.pollMax() : wait.multipliedBy(2);
        }
    }

    private void finish(PublishJob job, AccountRef ref, String mediaId, List<Item> items, Actor actor) {
        String permalink = null;
        if (mediaId != null) {
            try {
                JsonNode m = api.media(ref, mediaId);
                permalink = m.path("permalink").asString(null);
                media.upsert(job.accountId(), InstagramApi.toMedia(m), job.kind() == Kind.STORY, m.toString());
            } catch (RuntimeException e) {
                log.warn("Published {} but could not load it yet: {}", mediaId, e.getMessage());
            }
        }
        jobs.published(job.id(), mediaId, permalink, clock.instant());
        items.stream().map(Item::uploadId).filter(id -> id != null && !id.isBlank()).forEach(uploads::delete);
        audit.record(actor, "publish.publish", "@" + job.accountUsername(),
                "job " + job.id() + " " + job.kind() + " media " + (mediaId == null ? "(id unknown)" : mediaId), false, "ok");
        log.info("Publish job {} done: media {}", job.id(), mediaId);
    }

    private void fail(PublishJob job, String message, Actor actor) {
        jobs.failed(job.id(), message);
        audit.record(actor, "publish.publish", "@" + job.accountUsername(), "job " + job.id() + " " + job.kind(),
                false, "failed");
        log.warn("Publish job {} failed: {}", job.id(), message);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Default idempotency key: the same content for the same account maps to the same job. */
    static String contentKey(PublishRequest req) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String s = req.accountId() + "|" + req.kind() + "|" + req.caption() + "|" + req.items() + "|" + req.options();
            return "content-" + HexFormat.of().formatHex(sha.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String target(Account a) {
        return a == null ? "account ?" : "@" + a.username();
    }

    /** Audit summary: kind, item count, caption length; never caption text or URLs with tokens. */
    private static String summary(PublishRequest req, WriteResult result) {
        int len = req.caption() == null ? 0 : req.caption().codePointCount(0, req.caption().length());
        String s = req.kind() + " items=" + (req.items() == null ? 0 : req.items().size()) + " captionLength=" + len;
        return result == null || result.ok() ? s : s + " errors=" + result.errors().size();
    }

    private static void putIfPresent(Map<String, String> p, String key, String value) {
        if (value != null && !value.isBlank()) {
            p.put(key, value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
