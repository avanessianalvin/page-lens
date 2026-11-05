package com.pagelens.core.write;

import java.time.Instant;
import java.util.List;

/** Publish requests and jobs (plan section 2, "Publish photo, carousel, Reel, Story"). */
public final class PublishModels {

    private PublishModels() {
    }

    public enum Kind { IMAGE, REEL, STORY, CAROUSEL }

    public enum ItemType { IMAGE, VIDEO }

    /**
     * One media item: a public {@code url}, or (videos on Facebook Login accounts only) an {@code uploadId} from
     * {@link UploadStore}.
     */
    public record Item(ItemType type, String url, String uploadId) {
    }

    /** Kind-specific options; every field optional. */
    public record Options(Boolean shareToFeed, String coverUrl, Integer thumbOffsetMs, String altText) {
        public static final Options NONE = new Options(null, null, null, null);
    }

    /**
     * @param idempotencyKey client-generated (e.g. a UUID per "Publish" click). The same key never publishes twice.
     */
    public record PublishRequest(long accountId, Kind kind, String caption, List<Item> items, Options options,
                                 String idempotencyKey) {
    }

    public enum JobStatus { QUEUED, CREATING, PROCESSING, PUBLISHING, PUBLISHED, FAILED }

    public record PublishJob(long id, long accountId, String accountUsername, String idempotencyKey, Kind kind,
                             String caption, int itemCount, String actor, JobStatus status, String containerId,
                             String igMediaId, String permalink, String errorMessage, Instant createdAt,
                             Instant updatedAt, Instant publishedAt) {

        public boolean finished() {
            return status == JobStatus.PUBLISHED || status == JobStatus.FAILED;
        }
    }
}
