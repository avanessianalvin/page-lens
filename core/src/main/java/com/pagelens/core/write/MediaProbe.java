package com.pagelens.core.write;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;

import org.springframework.stereotype.Component;

import com.pagelens.core.write.PublishModels.ItemType;

/**
 * Pre-checks a media URL before Instagram is asked to fetch it: Meta needs a public, US-ASCII URL, JPEG images
 * of at most 8 MB, and videos within size limits. Private and loopback addresses are refused outright (Meta can't
 * reach them, and PageLens shouldn't probe the local network). If the server can't be checked, that's a warning,
 * not a block; Instagram makes the final call.
 */
@Component
public class MediaProbe {

    static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    static final long MAX_REEL_BYTES = 300L * 1024 * 1024;
    static final long MAX_STORY_VIDEO_BYTES = 100L * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Test-only escape hatch so tests can probe the local fake server. Never set in a real config. */
    private final boolean allowPrivateHostsForTests;

    public MediaProbe(@org.springframework.beans.factory.annotation.Value(
            "${pagelens.testing.allow-private-media-hosts:false}") boolean allowPrivateHostsForTests) {
        this.allowPrivateHostsForTests = allowPrivateHostsForTests;
    }

    /**
     * @param maxBytes limit for this use (images: 8 MB; Reels 300 MB; story video 100 MB)
     */
    public void check(String label, String url, ItemType type, long maxBytes, WriteResult.Builder report) {
        if (url == null || url.isBlank()) {
            report.error(label + ": URL is missing");
            return;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            report.error(label + ": not a valid URL");
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            report.error(label + ": must be an http(s) URL that Instagram can download");
            return;
        }
        if (!url.chars().allMatch(c -> c < 128)) {
            report.error(label + ": URL must be plain ASCII (percent-encode other characters); Meta rejects it otherwise");
            return;
        }
        if (uri.getHost() == null) {
            report.error(label + ": URL has no host");
            return;
        }
        if (!allowPrivateHostsForTests && isPrivate(uri.getHost())) {
            report.error(label + ": " + uri.getHost() + " is a local/private address; Instagram can only fetch "
                    + "media from a public server");
            return;
        }
        if (scheme.equals("http")) {
            report.warn(label + ": plain http; prefer https");
        }
        probe(label, uri, type, maxBytes, report);
    }

    private void probe(String label, URI uri, ItemType type, long maxBytes, WriteResult.Builder report) {
        HttpResponse<Void> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 405 || resp.statusCode() == 501) { // HEAD not allowed: ask for one byte
                resp = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Range", "bytes=0-0")
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
            }
        } catch (IOException e) {
            report.warn(label + ": could not reach the URL to check it (" + e.getClass().getSimpleName()
                    + "); Instagram will try anyway");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (resp.statusCode() >= 400) {
            report.error(label + ": the server answered HTTP " + resp.statusCode() + "; Instagram would fail to fetch it");
            return;
        }
        String contentType = resp.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        if (type == ItemType.IMAGE && !contentType.isEmpty() && !contentType.startsWith("image/jpeg")
                && !contentType.startsWith("image/jpg")) {
            report.error(label + ": Instagram only accepts JPEG images (server says " + contentType + ")");
        }
        if (type == ItemType.VIDEO && !contentType.isEmpty() && !contentType.startsWith("video/mp4")
                && !contentType.startsWith("video/quicktime")) {
            report.warn(label + ": expected MP4 or MOV video (server says " + contentType + ")");
        }
        OptionalLong length = resp.statusCode() == 206 ? OptionalLong.empty()
                : resp.headers().firstValueAsLong("content-length");
        if (length.isPresent() && length.getAsLong() > maxBytes) {
            report.error(label + ": file is " + mb(length.getAsLong()) + ", over Instagram's " + mb(maxBytes) + " limit");
        }
    }

    static boolean isPrivate(String host) {
        if (host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
                || host.toLowerCase(Locale.ROOT).endsWith(".local")) {
            return true;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || isUniqueLocalV6(a)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false; // unknown host: the probe will report it as unreachable
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0));
    }
}
