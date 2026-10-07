package com.pijava.mcp.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.mcp.McpJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code formatMcpLogMessage} 与 {@code McpServerLog}（{@code log.ts} 全文）。
 */
class McpServerLogTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:34:56.789Z");

    @TempDir
    Path root;

    private static Object params(String json) {
        try {
            return McpJson.mapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    // ------------------------------------------------------------- formatMessage

    @Test
    void formatsLevelLoggerAndData() {
        var line = McpServerLog.formatMessage("files",
                params("{\"level\":\"error\",\"logger\":\"db\",\"data\":\"boom\"}"), NOW);
        assertThat(line).isEqualTo("2026-10-07T12:34:56.789Z [files] error db: boom\n");
    }

    @Test
    void defaultsLevelToInfoAndDropsAnEmptyLogger() {
        var line = McpServerLog.formatMessage("files",
                params("{\"logger\":\"\",\"data\":\"hi\"}"), NOW);
        assertThat(line).isEqualTo("2026-10-07T12:34:56.789Z [files] info hi\n");
    }

    @Test
    void wrapsNonObjectParamsAsData() {
        var line = McpServerLog.formatMessage("files", "plain", NOW);
        assertThat(line).isEqualTo("2026-10-07T12:34:56.789Z [files] info plain\n");
    }

    @Test
    void wrapsArrayParamsAndSerializesThemCompactly() {
        var line = McpServerLog.formatMessage("files", params("[\"a\",1]"), NOW);
        assertThat(line).isEqualTo("2026-10-07T12:34:56.789Z [files] info [\"a\",1]\n");
    }

    @Test
    void rendersMissingDataAsJavascriptDoes() {
        var line = McpServerLog.formatMessage("files", params("{\"level\":\"warn\"}"), NOW);
        assertThat(line).isEqualTo("2026-10-07T12:34:56.789Z [files] warn undefined\n");
    }

    @Test
    void rendersExplicitNullDataAsNull() {
        var line = McpServerLog.formatMessage("files", params("{\"data\":null}"), NOW);
        assertThat(line).isEqualTo("2026-10-07T12:34:56.789Z [files] info null\n");
    }

    @Test
    void indentsContinuationLinesWithFourSpacesAndFoldsCrlf() {
        var line = McpServerLog.formatMessage("files", params("{\"data\":\"a\\r\\nb\\nc\"}"), NOW);
        assertThat(line).isEqualTo("2026-10-07T12:34:56.789Z [files] info a\n    b\n    c\n");
    }

    @Test
    void writesThreeFractionalDigits() {
        var line = McpServerLog.formatMessage("s", "x", Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(line).startsWith("2026-01-02T00:00:00.000Z [s] info x");
    }

    // ------------------------------------------------------------------- write

    @Test
    void createsParentDirectoriesAndAppends() throws IOException {
        var path = root.resolve("agent").resolve("mcp.log");
        var log = new McpServerLog(path);
        log.write("s", params("{\"data\":\"one\"}"));
        log.write("s", params("{\"data\":\"two\"}"));
        assertThat(read(path)).contains("] info one\n").contains("] info two\n");
    }

    @Test
    void rotatesOnceTheFilePassedTheLimit() throws IOException {
        var path = root.resolve("mcp.log");
        var log = new McpServerLog(path);
        log.write("s", params("{\"data\":\"" + "x".repeat((int) McpServerLog.MAX_LOG_BYTES) + "\"}"));
        log.write("s", params("{\"data\":\"after\"}"));

        assertThat(Files.size(root.resolve("mcp.log.1"))).isGreaterThan(McpServerLog.MAX_LOG_BYTES);
        assertThat(read(path)).contains("] info after\n");
        assertThat(read(path)).hasSizeLessThan(200);
    }

    @Test
    void doesNotRotateWhatAnotherProcessAlreadyMoved() throws IOException {
        var path = root.resolve("mcp.log");
        var log = new McpServerLog(path);
        log.write("s", params("{\"data\":\"" + "x".repeat((int) McpServerLog.MAX_LOG_BYTES) + "\"}"));
        // Another process rotated the file away; the cached size is now stale.
        Files.move(path, root.resolve("elsewhere.log"), StandardCopyOption.REPLACE_EXISTING);
        log.write("s", params("{\"data\":\"after\"}"));

        assertThat(root.resolve("mcp.log.1")).doesNotExist();
        assertThat(read(path)).contains("] info after\n");
    }

    @Test
    void ignoresWriteFailures() throws IOException {
        var path = root.resolve("mcp.log");
        Files.createDirectories(path);
        new McpServerLog(path).write("s", params("{\"data\":\"boom\"}"));
        assertThat(Files.isDirectory(path)).isTrue();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
