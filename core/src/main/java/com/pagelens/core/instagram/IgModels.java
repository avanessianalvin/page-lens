package com.pagelens.core.instagram;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Typed views of Graph API objects. Every count is nullable: NULL means "not returned", never zero. */
public final class IgModels {

    private IgModels() {
    }

    /**
     * Who to call the API as. {@link #toString()} never prints the token.
     *
     * @param accountId PageLens account id (null during OAuth, before the account exists)
     */
    public record AccountRef(Long accountId, String igUserId, LoginType loginType, String accessToken) {
        @Override
        public String toString() {
            return "AccountRef[accountId=" + accountId + ", igUserId=" + igUserId + ", loginType=" + loginType + "]";
        }
    }

    public record IgProfile(String igUserId, String username, String name, String accountType,
                            String profilePictureUrl, Long followersCount, Long followsCount, Long mediaCount) {
    }

    public record IgMedia(String id, String caption, String mediaType, String mediaProductType, String permalink,
                          String thumbnailUrl, String mediaUrl, Instant timestamp, Long likeCount,
                          Long commentsCount, String shortcode, JsonNode raw) {

        /** Product type, inferred when the API didn't return it (Instagram Login may omit it). */
        public String effectiveProductType() {
            if (mediaProductType != null && !mediaProductType.isBlank()) {
                return mediaProductType;
            }
            return "VIDEO".equals(mediaType) ? "REELS" : "FEED";
        }
    }

    public record IgComment(String id, String text, Instant timestamp, String username, Long likeCount,
                            Boolean hidden, List<IgComment> replies) {
    }

    /**
     * Result of an insights request.
     *
     * @param values      numeric metric values (missing metrics are absent, not zero)
     * @param raw         the full metric objects, for extra_metrics / later re-extraction
     * @param unsupported metrics the API rejected (logged, then skipped)
     */
    public record Insights(Map<String, Long> values, Map<String, JsonNode> raw, Set<String> unsupported) {
    }

    /** Facebook Login: an IG professional account found behind one of the user's Pages. */
    public record LinkedIgAccount(String pageId, String pageName, String igUserId, String igUsername) {
    }

    /** A token as returned by an OAuth exchange. {@link #toString()} never prints the token. */
    public record IssuedToken(String accessToken, String tokenType, Instant expiresAt, String igUserId,
                              String permissions) {
        @Override
        public String toString() {
            return "IssuedToken[type=" + tokenType + ", expiresAt=" + expiresAt + ", igUserId=" + igUserId + "]";
        }
    }
}
