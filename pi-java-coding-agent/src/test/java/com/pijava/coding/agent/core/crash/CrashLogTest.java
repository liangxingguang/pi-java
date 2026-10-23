package com.pijava.coding.agent.core.crash;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.pijava.coding.agent.extension.InstalledExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** pi {@code crash-log.test.ts} 对拍：record/take/clear/age 窗口/5 条截断/stack 匹配。 */
class CrashLogTest {

    @TempDir
    Path dir;

    private Path logPath() {
        return dir.resolve("crashes.json");
    }

    @Test
    void readMissingFileReturnsEmpty() {
        assertThat(CrashLog.read(logPath())).isEmpty();
    }

    @Test
    void readBadJsonReturnsEmpty() throws Exception {
        Files.writeString(logPath(), "{not json");
        assertThat(CrashLog.read(logPath())).isEmpty();
    }

    @Test
    void readNonArrayReturnsEmpty() throws Exception {
        Files.writeString(logPath(), "{\"timestamp\":\"t\"}");
        assertThat(CrashLog.read(logPath())).isEmpty();
    }

    @Test
    void readFiltersRecordsMissingTimestampOrMessage() throws Exception {
        Files.writeString(logPath(), """
            [
              {"timestamp":"2026-01-01T00:00:00Z","message":"boom","kind":"fatal_error",
               "version":"1","cwd":"/x"},
              {"message":"no timestamp"},
              {"timestamp":"2026-01-01T00:00:00Z"},
              "garbage"
            ]
            """);
        var records = CrashLog.read(logPath());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().message()).isEqualTo("boom");
        assertThat(records.getFirst().kind()).isEqualTo(CrashRecord.Kind.FATAL_ERROR);
    }

    @Test
    void recordAppendsAndRoundTrips() {
        var error = new IllegalStateException("boom");
        var record = CrashLog.record(
            CrashRecord.Kind.UNCAUGHT_EXCEPTION, error, "/tmp/s.jsonl", "/work", logPath());
        assertThat(record).isNotNull();
        assertThat(record.message()).isEqualTo("boom");
        assertThat(record.stack()).contains("IllegalStateException");
        assertThat(record.sessionFile()).isEqualTo("/tmp/s.jsonl");
        assertThat(record.notified()).isNull();

        var records = CrashLog.read(logPath());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().message()).isEqualTo("boom");
        assertThat(records.getFirst().kind()).isEqualTo(CrashRecord.Kind.UNCAUGHT_EXCEPTION);
    }

    @Test
    void recordEmptyMessageFallsBackToClassName() {
        var record = CrashLog.record(
            CrashRecord.Kind.FATAL_ERROR, new IllegalStateException(), null, "/w", logPath());
        assertThat(record).isNotNull();
        assertThat(record.message()).isEqualTo("IllegalStateException");
    }

    @Test
    void recordKeepsOnlyLastFive() {
        for (int i = 0; i < 7; i++) {
            CrashLog.record(CrashRecord.Kind.FATAL_ERROR,
                new RuntimeException("e" + i), null, "/w", logPath());
        }
        var records = CrashLog.read(logPath());
        assertThat(records).hasSize(CrashLog.MAX_CRASH_RECORDS);
        assertThat(records.getFirst().message()).isEqualTo("e2");
        assertThat(records.getLast().message()).isEqualTo("e6");
    }

    @Test
    void recordUnwritablePathReturnsNull() {
        var bad = dir.resolve("no/such/deep/dir").resolve("x").resolve("crashes.json");
        // dir 上 mkdirs 能成功 ⇒ 用一个「路径中间段是文件」的形状逼失败。
        try {
            var blocker = dir.resolve("blocker");
            Files.writeString(blocker, "file");
            bad = blocker.resolve("crashes.json");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(CrashLog.record(CrashRecord.Kind.FATAL_ERROR,
            new RuntimeException("x"), null, "/w", bad)).isNull();
    }

    @Test
    void takeUnnotifiedReturnsNewestWithinWindowAndMarksAllNotified() {
        long now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli();
        writeRaw(now - 1000, "old", false);
        appendRaw(now - 500, "new", false);

        var taken = CrashLog.takeUnnotified(logPath(), now);
        assertThat(taken).isNotNull();
        assertThat(taken.message()).isEqualTo("new");

        // 全量未通知记录都被标记（不只取出的那条）。
        var records = CrashLog.read(logPath());
        assertThat(records).allMatch(r -> Boolean.TRUE.equals(r.notified()));

        // 再取 ⇒ 无未通知。
        assertThat(CrashLog.takeUnnotified(logPath(), now)).isNull();
    }

    @Test
    void takeUnnotifiedSkipsRecordsOlderThanSevenDays() {
        long now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli();
        writeRaw(now - CrashLog.MAX_AGE_MILLIS - 1, "ancient", false);
        assertThat(CrashLog.takeUnnotified(logPath(), now)).isNull();
        // 过期记录未命中 ⇒ 不回写 notified（下次仍候选但永不过窗）。
        var records = CrashLog.read(logPath());
        assertThat(records.getFirst().notified()).isNull();
    }

    @Test
    void takeUnnotifiedSkipsUnparseableTimestamp() {
        long now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli();
        try {
            Files.writeString(logPath(), """
                [{"timestamp":"not-a-date","message":"boom","kind":"fatal_error"}]
                """);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(CrashLog.takeUnnotified(logPath(), now)).isNull();
    }

    @Test
    void clearDeletesFileAndToleratesMissing() {
        CrashLog.record(CrashRecord.Kind.FATAL_ERROR, new RuntimeException("x"),
            null, "/w", logPath());
        assertThat(logPath()).exists();
        CrashLog.clear(logPath());
        assertThat(logPath()).doesNotExist();
        CrashLog.clear(logPath()); // 已删 ⇒ 静默
        assertThat(logPath()).doesNotExist();
    }

    @Test
    void stackMatchesByJarFileNameCaseInsensitive() {
        var extensions = List.of(
            extension("acme-tools.jar", "acme-tools"),
            extension("other.jar", "other"));
        var matches = CrashLog.findExtensionStackMatches(
            "java.lang.Error\n\tat com.foo.Main.run(ACME-TOOLS.class:12)", extensions);
        assertThat(matches).containsExactly("acme-tools");
    }

    @Test
    void stackMatchesNullStackReturnsEmpty() {
        assertThat(CrashLog.findExtensionStackMatches(null,
            List.of(extension("a.jar", "a")))).isEmpty();
    }

    @Test
    void stackMatchesDeduplicateByName() {
        var extensions = List.of(
            extension("dup.jar", "dup"),
            extension("dup.jar", "dup"));
        var matches = CrashLog.findExtensionStackMatches("dup hit", extensions);
        assertThat(matches).containsExactly("dup");
    }

    private static InstalledExtension extension(String fileName, String name) {
        return new InstalledExtension(Path.of("/ext/" + fileName), fileName, name,
            "", "", List.of(), List.of(), List.of(), List.of());
    }

    private void writeRaw(long epochMillis, String message, boolean notified) {
        var json = """
            [{"timestamp":"%s","message":"%s","kind":"fatal_error"%s}]
            """.formatted(Instant.ofEpochMilli(epochMillis).toString(), message,
                notified ? ",\"notified\":true" : "");
        try {
            Files.writeString(logPath(), json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void appendRaw(long epochMillis, String message, boolean notified) {
        try {
            var existing = Files.readString(logPath());
            var insert = """
                ,{"timestamp":"%s","message":"%s","kind":"fatal_error"%s}
                """.formatted(Instant.ofEpochMilli(epochMillis).toString(), message,
                    notified ? ",\"notified\":true" : "");
            Files.writeString(logPath(),
                existing.substring(0, existing.lastIndexOf(']')) + insert + "]\n");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
