package com.pagelens.core.write;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.pagelens.core.config.PageLensProperties;

/**
 * Local staging for video files that will be sent to Meta via resumable upload (Facebook Login accounts only).
 * Files live under {@code pagelens.upload-dir} as {@code <uuid>.<ext>}; ids are UUIDs, so callers can never
 * address a path outside the folder. Files are deleted after a successful publish.
 */
@Component
public class UploadStore {

    public static final long MAX_BYTES = MediaProbe.MAX_REEL_BYTES;
    private static final Pattern ID = Pattern.compile("^[0-9a-f-]{36}\\.(mp4|mov)$");

    public record Upload(String id, long size, String originalName) {
    }

    private final Path dir;

    public UploadStore(PageLensProperties props) {
        this.dir = Path.of(props.uploadDir() == null ? "data/uploads" : props.uploadDir()).toAbsolutePath().normalize();
    }

    public Upload save(InputStream content, String originalName) {
        String ext = extension(originalName);
        if (!ext.equals("mp4") && !ext.equals("mov")) {
            throw new IllegalArgumentException("Only MP4 or MOV video files can be uploaded (images need a public URL)");
        }
        String id = UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(id);
            long size = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            if (size > MAX_BYTES) {
                Files.deleteIfExists(target);
                throw new IllegalArgumentException("Video is over Instagram's 300 MB limit");
            }
            if (size == 0) {
                Files.deleteIfExists(target);
                throw new IllegalArgumentException("The uploaded file is empty");
            }
            return new Upload(id, size, originalName);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the upload", e);
        }
    }

    /** The staged file for an upload id, if it exists. */
    public Optional<Path> resolve(String uploadId) {
        if (uploadId == null || !ID.matcher(uploadId).matches()) {
            return Optional.empty();
        }
        Path p = dir.resolve(uploadId).normalize();
        return p.startsWith(dir) && Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    public void delete(String uploadId) {
        resolve(uploadId).ifPresent(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // a leftover staging file is harmless
            }
        });
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
