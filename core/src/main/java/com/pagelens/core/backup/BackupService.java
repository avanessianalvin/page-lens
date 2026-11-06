package com.pagelens.core.backup;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pagelens.core.config.BackupProperties;
import com.pagelens.core.instagram.SecretMasker;
import com.pagelens.core.repo.AuditLogRepository;
import com.pagelens.core.repo.AuditLogRepository.Actor;
import com.pagelens.core.repo.BackupRunRepository;

/**
 * Backs up the {@code pagelens} schema with {@code pg_dump -Fc} (plan section 12). The database password goes to
 * pg_dump through {@code PGPASSWORD} in the child process's environment, never on the command line or in logs.
 * Files are written to a temporary name and renamed when complete, so a half-written dump is never mistaken for
 * a backup. Retention deletes dumps older than {@code keep-days} but always keeps the newest three.
 */
@Service
public class BackupService {

    public record BackupFile(String name, long sizeBytes, Instant createdAt) {
    }

    public record Connection(String host, int port, String database, String user, String password, String schema) {
        @Override
        public String toString() {
            return "Connection[" + user + "@" + host + ":" + port + "/" + database + " schema " + schema + "]";
        }
    }

    static final String PREFIX = "pagelens-";
    static final String SUFFIX = ".dump";
    private static final int KEEP_AT_LEAST = 3;
    private static final Duration TIMEOUT = Duration.ofMinutes(15);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final BackupProperties props;
    private final BackupRunRepository runs;
    private final AuditLogRepository audit;
    private final Clock clock;
    private final javax.sql.DataSource dataSource;
    private final String schema;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * The connection is read from the live pool (not from spring.datasource.* properties), so the dump always
     * targets the database the app is actually using.
     */
    public BackupService(BackupProperties props, BackupRunRepository runs, AuditLogRepository audit, Clock clock,
                         javax.sql.DataSource dataSource,
                         @Value("${spring.flyway.default-schema:pagelens}") String schema) {
        this.props = props;
        this.runs = runs;
        this.audit = audit;
        this.clock = clock;
        this.dataSource = dataSource;
        this.schema = schema;
    }

    Connection connection() {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource h) {
            return parse(h.getJdbcUrl(), h.getUsername(), h.getPassword(), schema);
        }
        throw new IllegalStateException("Backups need a Hikari connection pool (got " + dataSource.getClass().getName() + ")");
    }

    public Path dir() {
        return Path.of(props.dir() == null || props.dir().isBlank() ? "backups" : props.dir()).toAbsolutePath().normalize();
    }

    /** Runs a backup now (synchronously). Returns the run's final state. */
    public BackupRunRepository.BackupRun run(Actor actor) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A backup is already running");
        }
        long id = runs.start(actor.name().toLowerCase());
        try {
            Path file = dump();
            long size = Files.size(file);
            runs.succeeded(id, file.getFileName().toString(), size);
            int removed = prune();
            audit.record(actor, "backup.run", file.getFileName().toString(), size + " bytes, pruned " + removed, false, "ok");
            log.info("Backup written: {} ({} bytes); {} old backup(s) pruned", file.getFileName(), size, removed);
        } catch (RuntimeException | IOException e) {
            String msg = SecretMasker.mask(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            runs.failed(id, msg);
            audit.record(actor, "backup.run", "-", msg, false, "failed");
            log.error("Backup failed: {}", msg);
        } finally {
            running.set(false);
        }
        return runs.recent(50).stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    public boolean pgDumpFound() {
        return pgDump().isPresent();
    }

    public List<BackupFile> list() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(BackupService::isBackup)
                    .map(p -> {
                        try {
                            return new BackupFile(p.getFileName().toString(), Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant());
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(BackupFile::createdAt).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------------------------------ internals

    private Path dump() throws IOException {
        String pgDump = pgDump().orElseThrow(() -> new IllegalStateException(
                "pg_dump not found. Install PostgreSQL client tools or set PAGELENS_PG_DUMP to its full path."));
        Path dir = dir();
        Files.createDirectories(dir);
        String name = PREFIX + STAMP.format(clock.instant()) + SUFFIX;
        Path partial = dir.resolve(name + ".partial");
        Path target = dir.resolve(name);
        Connection connection = connection();
        List<String> cmd = command(pgDump, connection, partial);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", connection.password() == null ? "" : connection.password());
        pb.environment().put("PGCONNECT_TIMEOUT", "15");
        Process p = pb.start();
        String output;
        try {
            boolean done = p.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!done) {
                p.destroyForcibly();
                throw new IllegalStateException("pg_dump did not finish within " + TIMEOUT.toMinutes() + " minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IllegalStateException("Backup interrupted");
        }
        if (p.exitValue() != 0) {
            Files.deleteIfExists(partial);
            throw new IllegalStateException("pg_dump failed (exit " + p.exitValue() + "): " + truncate(output, 500));
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** The pg_dump command line. Contains no password (that goes in PGPASSWORD). */
    static List<String> command(String pgDump, Connection c, Path file) {
        return List.of(pgDump, "--host", c.host(), "--port", String.valueOf(c.port()), "--username", c.user(),
                "--dbname", c.database(), "--schema", c.schema(), "--format", "custom", "--no-owner", "--no-privileges",
                "--file", file.toString());
    }

    /** Deletes backups older than keep-days, always keeping the newest {@value #KEEP_AT_LEAST}. */
    int prune() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(Math.max(1, props.keepDays())));
        List<BackupFile> files = list();
        int removed = 0;
        for (int i = KEEP_AT_LEAST; i < files.size(); i++) {
            if (files.get(i).createdAt().isBefore(cutoff)) {
                try {
                    Files.deleteIfExists(dir().resolve(files.get(i).name()));
                    removed++;
                } catch (IOException e) {
                    log.warn("Could not delete old backup {}", files.get(i).name());
                }
            }
        }
        return removed;
    }

    Optional<String> pgDump() {
        if (props.pgDump() != null && !props.pgDump().isBlank()) {
            return Optional.of(props.pgDump().trim());
        }
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "pg_dump.exe" : "pg_dump";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir.isBlank() ? "." : dir, exe);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate.toString());
            }
        }
        Path pgRoot = Path.of("C:\\Program Files\\PostgreSQL");
        if (Files.isDirectory(pgRoot)) {
            try (Stream<Path> versions = Files.list(pgRoot)) {
                return versions.map(v -> v.resolve("bin").resolve("pg_dump.exe")).filter(Files::isExecutable)
                        .max(Comparator.comparing(BackupService::versionOf)).map(Path::toString);
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static int versionOf(Path pgDumpExe) {
        try {
            return Integer.parseInt(pgDumpExe.getParent().getParent().getFileName().toString().replaceAll("\\D.*", ""));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** {@code jdbc:postgresql://host:port/db?x=y} → connection parts. */
    static Connection parse(String jdbcUrl, String user, String password, String schema) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            return new Connection("localhost", 5432, "postgres", user, password, schema);
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String db = uri.getPath() == null || uri.getPath().length() <= 1 ? "postgres" : uri.getPath().substring(1);
        return new Connection(uri.getHost() == null ? "localhost" : uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(),
                db, user, password, schema);
    }

    private static boolean isBackup(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
