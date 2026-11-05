package com.pagelens.core.write;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.pagelens.core.instagram.LoginType;
import com.pagelens.core.instagram.MetaOAuthClient;
import com.pagelens.core.repo.AccountRepository.Account;
import com.pagelens.core.write.PublishModels.Item;
import com.pagelens.core.write.PublishModels.ItemType;
import com.pagelens.core.write.PublishModels.Kind;
import com.pagelens.core.write.PublishModels.Options;
import com.pagelens.core.write.PublishModels.PublishRequest;

/**
 * Meta's publishing rules (docs/API_NOTES.md, Phase 3), checked before anything is sent. Errors block;
 * warnings inform.
 */
@Component
public class PublishValidator {

    static final int MAX_CAPTION = 2200;
    static final int MAX_HASHTAGS = 30;
    static final int MAX_MENTIONS = 20;
    static final int MAX_ALT_TEXT = 1000;
    static final int MIN_CAROUSEL = 2;
    static final int MAX_CAROUSEL = 20;

    private static final Pattern HASHTAG = Pattern.compile("(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_]+");
    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}_.])@[A-Za-z0-9._]+");

    private final MediaProbe probe;
    private final UploadStore uploads;

    public PublishValidator(MediaProbe probe, UploadStore uploads) {
        this.probe = probe;
        this.uploads = uploads;
    }

    /**
     * @param storedScopes the token's granted scopes as stored at connect time, or null if unknown (pasted token)
     */
    public void validate(PublishRequest req, Account account, String storedScopes, WriteResult.Builder r) {
        if (!"ACTIVE".equals(account.status())) {
            r.error("@" + account.username() + " needs reconnecting before it can publish");
        }
        String publishScope = MetaOAuthClient.publishScope(account.loginType());
        if (storedScopes == null) {
            r.warn("PageLens doesn't know whether this token includes '" + publishScope
                    + "'. If Instagram refuses, reconnect the account.");
        } else if (!storedScopes.contains(publishScope)) {
            r.error("This account was connected without the publishing permission (" + publishScope
                    + "). Reconnect it on the Accounts page to grant it.");
        }
        if (req.kind() == null) {
            r.error("Choose what to publish: IMAGE, REEL, STORY or CAROUSEL");
            return;
        }
        List<Item> items = req.items() == null ? List.of() : req.items();
        Options o = req.options() == null ? Options.NONE : req.options();
        r.detail("kind", req.kind()).detail("items", items.size());

        switch (req.kind()) {
            case IMAGE -> {
                exactlyOne(items, ItemType.IMAGE, "A photo post needs exactly one image", r);
                items.forEach(i -> media("Image", i, account, MediaProbe.MAX_IMAGE_BYTES, r));
            }
            case REEL -> {
                exactlyOne(items, ItemType.VIDEO, "A Reel needs exactly one video", r);
                items.forEach(i -> media("Video", i, account, MediaProbe.MAX_REEL_BYTES, r));
                if (o.coverUrl() != null && !o.coverUrl().isBlank()) {
                    probe.check("Cover image", o.coverUrl(), ItemType.IMAGE, MediaProbe.MAX_IMAGE_BYTES, r);
                }
                if (o.thumbOffsetMs() != null && o.thumbOffsetMs() < 0) {
                    r.error("Thumbnail offset must be 0 or more milliseconds");
                }
            }
            case STORY -> {
                if (items.size() != 1) {
                    r.error("A story needs exactly one image or video");
                }
                items.forEach(i -> media(i.type() == ItemType.VIDEO ? "Video" : "Image", i, account,
                        i.type() == ItemType.VIDEO ? MediaProbe.MAX_STORY_VIDEO_BYTES : MediaProbe.MAX_IMAGE_BYTES, r));
                if (req.caption() != null && !req.caption().isBlank()) {
                    r.warn("Stories have no caption through the API; the caption will be ignored");
                }
            }
            case CAROUSEL -> {
                if (items.size() < MIN_CAROUSEL || items.size() > MAX_CAROUSEL) {
                    r.error("A carousel needs " + MIN_CAROUSEL + " to " + MAX_CAROUSEL + " items (got " + items.size() + ")");
                }
                for (int i = 0; i < items.size(); i++) {
                    Item it = items.get(i);
                    media("Item " + (i + 1), it, account,
                            it.type() == ItemType.VIDEO ? MediaProbe.MAX_REEL_BYTES : MediaProbe.MAX_IMAGE_BYTES, r);
                }
            }
        }
        if (req.kind() != Kind.REEL && (o.shareToFeed() != null || o.coverUrl() != null || o.thumbOffsetMs() != null)) {
            r.warn("Share-to-feed, cover and thumbnail options only apply to Reels and will be ignored");
        }
        if (o.altText() != null && !o.altText().isBlank()) {
            if (req.kind() != Kind.IMAGE) {
                r.warn("Alt text is only supported for single-image posts and will be ignored");
            } else if (o.altText().codePointCount(0, o.altText().length()) > MAX_ALT_TEXT) {
                r.error("Alt text is longer than " + MAX_ALT_TEXT + " characters");
            }
        }
        if (req.kind() != Kind.STORY) {
            caption(req.caption(), r);
        }
    }

    static void caption(String caption, WriteResult.Builder r) {
        if (caption == null || caption.isEmpty()) {
            return;
        }
        int length = caption.codePointCount(0, caption.length());
        int tags = count(HASHTAG, caption);
        int mentions = count(MENTION, caption);
        r.detail("captionLength", length).detail("hashtags", tags).detail("mentions", mentions);
        if (length > MAX_CAPTION) {
            r.error("Caption is " + length + " characters; Instagram allows " + MAX_CAPTION);
        }
        if (tags > MAX_HASHTAGS) {
            r.error("Caption has " + tags + " hashtags; Instagram allows " + MAX_HASHTAGS);
        }
        if (mentions > MAX_MENTIONS) {
            r.error("Caption has " + mentions + " @-mentions; Instagram allows " + MAX_MENTIONS);
        }
    }

    private void media(String label, Item item, Account account, long maxBytes, WriteResult.Builder r) {
        if (item.type() == null) {
            r.error(label + ": type must be IMAGE or VIDEO");
            return;
        }
        boolean hasUrl = item.url() != null && !item.url().isBlank();
        boolean hasUpload = item.uploadId() != null && !item.uploadId().isBlank();
        if (hasUrl == hasUpload) {
            r.error(label + ": give either a public URL or an uploaded file, not " + (hasUrl ? "both" : "neither"));
            return;
        }
        if (hasUpload) {
            if (item.type() != ItemType.VIDEO) {
                r.error(label + ": only videos can be uploaded from this computer; images need a public URL");
            } else if (account.loginType() != LoginType.FACEBOOK_LOGIN) {
                r.error(label + ": uploading a local video needs an account connected with Facebook Login "
                        + "(Instagram Login only accepts public URLs)");
            } else {
                Path p = uploads.resolve(item.uploadId()).orElse(null);
                if (p == null) {
                    r.error(label + ": the uploaded file is no longer available; upload it again");
                } else {
                    try {
                        if (Files.size(p) > maxBytes) {
                            r.error(label + ": file is over Instagram's " + MediaProbe.mb(maxBytes) + " limit");
                        }
                    } catch (java.io.IOException e) {
                        r.error(label + ": the uploaded file can't be read");
                    }
                }
            }
            return;
        }
        probe.check(label, item.url(), item.type(), maxBytes, r);
    }

    private static void exactlyOne(List<Item> items, ItemType type, String message, WriteResult.Builder r) {
        if (items.size() != 1 || items.getFirst().type() != type) {
            r.error(message);
        }
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
