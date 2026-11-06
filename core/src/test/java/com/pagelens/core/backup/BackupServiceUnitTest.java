package com.pagelens.core.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pagelens.core.config.BackupProperties;

class BackupServiceUnitTest {

    @Test
    void parsesJdbcUrls() {
        var c = BackupService.parse("jdbc:postgresql://db.local:6543/postgres?sslmode=disable", "pagelens", "pw", "pagelens");
        assertThat(c.host()).isEqualTo("db.local");
        assertThat(c.port()).isEqualTo(6543);
        assertThat(c.database()).isEqualTo("postgres");
        assertThat(c.toString()).doesNotContain("pw"); // never print the password

        var d = BackupService.parse("jdbc:postgresql://localhost/mydb", "u", null, "s");
        assertThat(d.port()).isEqualTo(5432);
        assertThat(d.database()).isEqualTo("mydb");
    }

    @Test
    void commandNeverContainsThePassword() {
        var c = BackupService.parse("jdbc:postgresql://localhost:5432/postgres", "pagelens", "s3cret-pw", "pagelens");
        var cmd = BackupService.command("pg_dump", c, Path.of("out.dump"));
        assertThat(cmd).doesNotContain("s3cret-pw").contains("--schema", "pagelens", "--format", "custom", "--no-owner");
        assertThat(String.join(" ", cmd)).doesNotContain("s3cret-pw");
    }

    @Test
    void pruneDeletesOldBackupsButKeepsTheNewestThree(@TempDir Path dir) throws Exception {
        Instant now = Instant.parse("2026-11-06T03:15:00Z");
        for (int age : new int[] {1, 20, 30, 40, 50}) {
            Path f = Files.writeString(dir.resolve("pagelens-day" + age + ".dump"), "x");
            Files.setLastModifiedTime(f, FileTime.from(now.minus(age, ChronoUnit.DAYS)));
        }
        Files.writeString(dir.resolve("unrelated.txt"), "keep me");
        var service = new BackupService(new BackupProperties(true, "", dir.toString(), 14, ""), null, null,
                Clock.fixed(now, ZoneOffset.UTC), null, "pagelens");

        assertThat(service.prune()).isEqualTo(2); // 40 and 50 days; 20 and 30 survive as part of the newest three
        assertThat(service.list()).extracting(BackupService.BackupFile::name)
                .containsExactly("pagelens-day1.dump", "pagelens-day20.dump", "pagelens-day30.dump");
        assertThat(dir.resolve("unrelated.txt")).exists();
    }
}
