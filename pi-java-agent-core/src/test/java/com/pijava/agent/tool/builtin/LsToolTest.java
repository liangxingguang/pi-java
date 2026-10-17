package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.agent.tool.TestContexts;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;

/**
 * 批 3/docs/22 §4：ls 参数集换血（{@code recursive}→{@code limit}）、裸名列表、
 * {@code /} 目录后缀、大小写不敏感排序、告示与三错误文本逐字。
 */
class LsToolTest {

    @TempDir Path tmp;

    // ── 契约面 ────────────────────────────────────────────────────

    @Test
    void descriptionIsPiVerbatim() {
        assertThat(LsTool.create().description()).isEqualTo(
            "List directory contents. Returns entries sorted alphabetically, with '/' "
                + "suffix for directories. Includes dotfiles. Output is truncated to "
                + "500 entries or 50KB (whichever is hit first).");
    }

    @Test
    void promptSnippetIsPiVerbatim() {
        assertThat(LsTool.create().promptSnippet()).isEqualTo("List directory contents");
    }

    @Test
    void schemaHasPathAndLimitOnly() {
        var schema = LsTool.create().inputSchema();
        @SuppressWarnings("unchecked")
        var p = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(p.keySet()).containsExactlyInAnyOrder("path", "limit");
        assertThat(p.get("path").get("description"))
            .isEqualTo("Directory to list (default: current directory)");
        assertThat(p.get("limit").get("description"))
            .isEqualTo("Maximum number of entries to return (default: 500)");
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).isEmpty();
    }

    @Test
    void prepareArgumentsParsesLimit() {
        var input = LsTool.create().prepareArguments(Map.of("path", "src", "limit", 10));
        assertThat(input.path()).hasValue("src");
        assertThat(input.limit()).hasValue(10);
    }

    // ── 输出形 ────────────────────────────────────────────────────

    @Test
    void bareNamesSortedCaseInsensitive() throws Exception {
        Files.writeString(tmp.resolve("B.txt"), "1");
        Files.writeString(tmp.resolve("a.txt"), "2");
        Files.writeString(tmp.resolve("C.txt"), "3");

        var result = ls(input());

        assertThat(textOf(result)).isEqualTo("a.txt\nB.txt\nC.txt");
    }

    @Test
    void directoriesGetSlashSuffix() throws Exception {
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("file.txt"), "1");

        var result = ls(input());

        assertThat(textOf(result)).isEqualTo("file.txt\nsub/");
    }

    @Test
    void dotfilesAreIncluded() throws Exception {
        Files.writeString(tmp.resolve(".hidden"), "1");
        Files.writeString(tmp.resolve("visible.txt"), "2");

        var result = ls(input());

        assertThat(textOf(result)).isEqualTo(".hidden\nvisible.txt");
    }

    @Test
    void emptyDirectoryReturnsVerbatimMarker() throws Exception {
        var result = ls(input());

        assertThat(textOf(result)).isEqualTo("(empty directory)");
        assertThat(result.details()).isNull();
    }

    // ── 错误文本 ───────────────────────────────────────────────────

    @Test
    void pathNotFoundThrowsVerbatim() {
        String missing = Path.of(tmp.toString(), "missing").toString();
        assertThatThrownBy(() -> ls(new LsTool.LsInput(
                Optional.of("missing"), Optional.empty())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Path not found: " + missing);
    }

    @Test
    void regularFileThrowsNotADirectory() throws Exception {
        Files.writeString(tmp.resolve("file.txt"), "1");
        String file = tmp.resolve("file.txt").toString();
        assertThatThrownBy(() -> ls(new LsTool.LsInput(
                Optional.of("file.txt"), Optional.empty())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Not a directory: " + file);
    }

    // ── limit 与告示 ───────────────────────────────────────────────

    @Test
    void entryLimitProducesVerbatimNoticeAndDetails() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "1");
        Files.writeString(tmp.resolve("b.txt"), "2");
        Files.writeString(tmp.resolve("c.txt"), "3");

        var result = ls(new LsTool.LsInput(Optional.empty(), Optional.of(2)));

        var text = textOf(result);
        assertThat(text).isEqualTo(
            "a.txt\nb.txt\n\n[2 entries limit reached. Use limit=4 for more]");
        assertThat(result.details().entryLimitReached()).isEqualTo(2);
        assertThat(result.details().truncation()).isNull();
    }

    @Test
    void byteLimitProducesVerbatimNoticeAndDetails() throws Exception {
        // 300 个 180 字长名 ≈ 55KB > 50KB；limit 默认 500 > 300 不触发 entries 告示。
        String name = "n".repeat(180) + ".txt";
        for (int i = 0; i < 300; i++) {
            Files.writeString(tmp.resolve(i + name), "x");
        }

        var result = ls(input());

        assertThat(textOf(result)).endsWith("\n\n[50.0KB limit reached]");
        assertThat(result.details().truncation()).isNotNull();
        assertThat(result.details().truncation().truncated()).isTrue();
        assertThat(result.details().entryLimitReached()).isNull();
    }

    @Test
    void detailsAreNullWhenNothingWasTruncated() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "1");

        var result = ls(input());

        assertThat(result.details()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────

    private LsTool.LsInput input() {
        return new LsTool.LsInput(Optional.empty(), Optional.empty());
    }

    private ToolResult<LsTool.LsDetails> ls(LsTool.LsInput params) throws Exception {
        return LsTool.create().execute("id", params, null, null, TestContexts.at(tmp));
    }

    private static String textOf(ToolResult<LsTool.LsDetails> result) {
        return ((ContentBlock.TextContent) result.content().get(0)).text();
    }
}
