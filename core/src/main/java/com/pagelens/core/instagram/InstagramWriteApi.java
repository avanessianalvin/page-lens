package com.pagelens.core.instagram;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pagelens.core.config.MetaProperties;
import com.pagelens.core.instagram.IgModels.AccountRef;

import tools.jackson.databind.JsonNode;

/**
 * Instagram write calls (docs/API_NOTES.md, "Phase 3"). Every method sends exactly one request and never
 * retries: whether to try again is decided by the caller after checking state on Instagram.
 * Only {@code com.pagelens.core.write} may call this class, behind the write-actions switch and the audit trail.
 */
@Component
public class InstagramWriteApi {

    /** Container states from {@code GET /<container>?fields=status_code}. */
    public enum ContainerStatus { IN_PROGRESS, FINISHED, PUBLISHED, ERROR, EXPIRED, UNKNOWN }

    public record Container(String id, String uploadUri) {
    }

    public record ContainerState(ContainerStatus status, String detail) {
    }

    public record PublishQuota(Integer used, Integer total, Long durationSeconds) {
    }

    private final GraphApiClient client;
    private final MetaProperties meta;

    public InstagramWriteApi(GraphApiClient client, MetaProperties meta) {
        this.client = client;
        this.meta = meta;
    }

    // ------------------------------------------------------------------------------------------ comments

    /** Replies to a top-level comment; returns the new comment's id. */
    public String reply(AccountRef a, String igCommentId, String message) {
        JsonNode r = client.postWrite(a.accountId(), base(a) + "/" + igCommentId + "/replies",
                form(a, Map.of("message", message)));
        return r.path("id").asString(null);
    }

    public void setHidden(AccountRef a, String igCommentId, boolean hidden) {
        client.postWrite(a.accountId(), base(a) + "/" + igCommentId, form(a, Map.of("hide", String.valueOf(hidden))));
    }

    public void deleteComment(AccountRef a, String igCommentId) {
        client.deleteWrite(a.accountId(), base(a) + "/" + igCommentId, Map.of("access_token", a.accessToken()));
    }

    // ------------------------------------------------------------------------------------------ publishing

    /**
     * Creates a media container. {@code params} are Meta's container parameters (image_url, video_url,
     * media_type, caption, children, is_carousel_item, ...). Returns the container id, and the upload URI when
     * {@code upload_type=resumable}.
     */
    public Container createContainer(AccountRef a, Map<String, String> params) {
        JsonNode r = client.postWrite(a.accountId(), base(a) + "/" + a.igUserId() + "/media", form(a, params));
        String id = r.path("id").asString(null);
        if (id == null) {
            throw new IllegalStateException("Instagram returned no container id");
        }
        return new Container(id, r.path("uri").asString(null));
    }

    /** Resumable upload of a local video into a container (Facebook Login only). */
    public void uploadVideo(AccountRef a, String uploadUri, Path file, long size, long offset) {
        String uri = uploadUri.replaceFirst("^https://rupload\\.facebook\\.com", meta.endpoints().rupload());
        JsonNode r = client.uploadFile(a.accountId(), uri, Map.of(
                "Authorization", "OAuth " + a.accessToken(),
                "offset", String.valueOf(offset),
                "file_size", String.valueOf(size)), file, offset);
        if (r.has("success") && !r.path("success").asBoolean()) {
            throw new IllegalStateException("Video upload was not accepted: " + r.path("message").asString(""));
        }
    }

    public ContainerState containerState(AccountRef a, String containerId) {
        JsonNode r = client.get(a.accountId(), base(a) + "/" + containerId,
                Map.of("fields", "status_code,status", "access_token", a.accessToken()));
        String code = r.path("status_code").asString("UNKNOWN");
        ContainerStatus status;
        try {
            status = ContainerStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            status = ContainerStatus.UNKNOWN;
        }
        return new ContainerState(status, r.path("status").asString(null));
    }

    /** Bytes Meta has received for a resumable upload (to resume from), or -1 if unknown. */
    public long uploadedBytes(AccountRef a, String containerId) {
        JsonNode r = client.get(a.accountId(), base(a) + "/" + containerId,
                Map.of("fields", "video_status", "access_token", a.accessToken()));
        JsonNode t = r.path("video_status").path("uploading_phase").path("bytes_transferred");
        return t.isNumber() ? t.asLong() : -1;
    }

    /** Publishes a FINISHED container; returns the new IG media id. */
    public String publish(AccountRef a, String containerId) {
        JsonNode r = client.postWrite(a.accountId(), base(a) + "/" + a.igUserId() + "/media_publish",
                form(a, Map.of("creation_id", containerId)));
        String id = r.path("id").asString(null);
        if (id == null) {
            throw new IllegalStateException("Instagram returned no media id for the publish call");
        }
        return id;
    }

    public PublishQuota quota(AccountRef a) {
        JsonNode r = client.get(a.accountId(), base(a) + "/" + a.igUserId() + "/content_publishing_limit",
                Map.of("fields", "config,quota_usage", "access_token", a.accessToken()));
        JsonNode d = r.path("data").path(0);
        return new PublishQuota(d.path("quota_usage").isNumber() ? d.path("quota_usage").asInt() : null,
                d.path("config").path("quota_total").isNumber() ? d.path("config").path("quota_total").asInt() : null,
                d.path("config").path("quota_duration").isNumber() ? d.path("config").path("quota_duration").asLong() : null);
    }

    /** Permalink and timestamp of a freshly published media (best effort, for the UI). */
    public JsonNode media(AccountRef a, String igMediaId) {
        return client.get(a.accountId(), base(a) + "/" + igMediaId,
                Map.of("fields", String.join(",", List.of(InstagramApi.MEDIA_FIELDS_MINIMAL, "caption", "media_product_type")),
                        "access_token", a.accessToken()));
    }

    private String base(AccountRef a) {
        return client.graphBase(a.loginType());
    }

    private static Map<String, String> form(AccountRef a, Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>(params);
        out.put("access_token", a.accessToken()); // in the body, so it never appears in a URL
        return out;
    }
}
