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
 * 批 3/docs/22 §2：grep 模型可见契约逐字（schema 七参/描述/snippet/输出形/告示），
 * 纯 Java 引擎（裁决①：不移植 rg 外壳）。
 */
class GrepToolTest {

    @TempDir Path tmp;

    // ── 契约面 ────────────────────────────────────────────────────

    @Test
    void descriptionIsPiVerbatim() {
        assertThat(GrepTool.create().description()).isEqualTo(
            "Search file contents for a pattern. Returns matching lines with file paths "
                + "and line numbers. Respects .gitignore. Output is truncated to 100 matches "
                + "or 50KB (whichever is hit first). Long lines are truncated to 500 chars.");
    }

    @Test
    void promptSnippetIsPiVerbatim() {
        assertThat(GrepTool.create().promptSnippet())
            .isEqualTo("Search file contents for patterns (respects .gitignore)");
    }

    @Test
    void schemaHasPiSevenParametersVerbatim() {
        var schema = GrepTool.create().inputSchema();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).containsExactly("pattern");
        @SuppressWarnings("unchecked")
        var p = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(p.keySet()).containsExactlyInAnyOrder(
            "pattern", "path", "glob", "ignoreCase", "literal", "context", "limit");
        assertThat(p.get("pattern").get("description"))
            .isEqualTo("Search pattern (regex or literal string)");
        assertThat(p.get("path").get("description"))
            .isEqualTo("Directory or file to search (default: current directory)");
        assertThat(p.get("glob").get("description"))
            .isEqualTo("Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'");
        assertThat(p.get("ignoreCase").get("description"))
            .isEqualTo("Case-insensitive search (default: false)");
        assertThat(p.get("literal").get("description"))
            .isEqualTo("Treat pattern as literal string instead of regex (default: false)");
        assertThat(p.get("context").get("description"))
            .isEqualTo("Number of lines to show before and after each match (default: 0)");
        assertThat(p.get("limit").get("description"))
            .isEqualTo("Maximum number of matches to return (default: 100)");
    }

    @Test
    void prepareArgumentsParsesAllSeven() {
        var input = GrepTool.create().prepareArguments(Map.of(
            "pattern", "needle", "path", "src", "glob", "*.txt",
            "ignoreCase", true, "literal", true, "context", 2, "limit", 5));
        assertThat(input.pattern()).isEqualTo("needle");
        assertThat(input.path()).hasValue("src");
        assertThat(input.glob()).hasValue("*.txt");
        assertThat(input.ignoreCase()).hasValue(true);
        assertThat(input.literal()).hasValue(true);
        assertThat(input.context()).hasValue(2);
        assertThat(input.limit()).hasValue(5);
    }

    // ── 输出形 ────────────────────────────────────────────────────

    @Test
    void noMatchReturnsVerbatimMessage() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "hello world");

        var result = grep(new GrepTool.GrepInput("NOTFOUND",
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(textOf(result)).isEqualTo("No matches found");
        assertThat(result.details()).isNull();
    }

    @Test
    void pathNotFoundThrowsVerbatim() {
        String missing = Path.of(tmp.toString(), "missing").toString();
        assertThatThrownBy(() -> grep(new GrepTool.GrepInput("x",
                Optional.of("missing"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Path not found: " + missing);
    }

    @Test
    void matchLinesAreRelativeToSearchDirWithLineNumbers() throws Exception {
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/a.txt"), "one\ntwo needle\nthree");

        var result = grep(input("needle"));

        assertThat(textOf(result)).isEqualTo("sub/a.txt:2: two needle");
    }

    @Test
    void singleFileSearchUsesBasename() throws Exception {
        var file = tmp.resolve("solo.txt");
        Files.writeString(file, "alpha\nbeta needle");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.of("solo.txt"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(textOf(result)).isEqualTo("solo.txt:2: beta needle");
    }

    @Test
    void contextLinesUseDashFormAroundMatch() throws Exception {
        Files.writeString(tmp.resolve("f.txt"), "l1\nl2\nl3 needle\nl4\nl5");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(1), Optional.empty()));

        assertThat(textOf(result)).isEqualTo(
            "f.txt-2- l2\nf.txt:3: l3 needle\nf.txt-4- l4");
    }

    @Test
    void contextWindowClampsAtFileEdges() throws Exception {
        Files.writeString(tmp.resolve("f.txt"), "l1 needle\nl2\nl3\nl4");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(2), Optional.empty()));

        assertThat(textOf(result)).isEqualTo(
            "f.txt:1: l1 needle\nf.txt-2- l2\nf.txt-3- l3");
    }

    // ── 参数行为 ───────────────────────────────────────────────────

    @Test
    void ignoreCaseMatchesUppercase() throws Exception {
        Files.writeString(tmp.resolve("f.txt"), "NEEDLE here");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.empty(), Optional.of(true),
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(textOf(result)).isEqualTo("f.txt:1: NEEDLE here");
    }

    @Test
    void literalTreatsPatternAsPlainString() throws Exception {
        Files.writeString(tmp.resolve("f.txt"), "axb\na.b");

        var result = grep(new GrepTool.GrepInput("a.b",
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(true), Optional.empty(), Optional.empty()));

        assertThat(textOf(result)).isEqualTo("f.txt:2: a.b");
    }

    @Test
    void globFiltersFilesByBasename() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "needle");
        Files.writeString(tmp.resolve("b.md"), "needle");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.of("*.txt"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(textOf(result)).isEqualTo("a.txt:1: needle");
    }

    @Test
    void globWithSlashAnchorsAtSearchRoot() throws Exception {
        Files.createDirectories(tmp.resolve("sub/deep"));
        Files.writeString(tmp.resolve("sub/x.txt"), "needle");
        Files.writeString(tmp.resolve("sub/deep/y.txt"), "needle");
        Files.writeString(tmp.resolve("z.txt"), "needle");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.of("sub/*.txt"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(textOf(result)).isEqualTo("sub/x.txt:1: needle");
    }

    @Test
    void doubleStarSlashGlobMatchesZeroSegmentsAtSearchRoot() throws Exception {
        // rg glob 语义：**/ 匹配零或多段——schema 自证例 '**/*.spec.ts'
        // 对搜索根下的直接文件也必须命中（java PathMatcher 的 ** 不吃零段，故走翻译器）。
        Files.writeString(tmp.resolve("a.spec.ts"), "needle");
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/b.spec.ts"), "needle");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.of("**/*.spec.ts"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(textOf(result).split("\n")).containsExactlyInAnyOrder(
            "a.spec.ts:1: needle", "sub/b.spec.ts:1: needle");
    }

    @Test
    void doubleStarInMiddleMatchesDirectChildren() throws Exception {
        Files.createDirectories(tmp.resolve("sub/deep"));
        Files.createDirectories(tmp.resolve("other"));
        Files.writeString(tmp.resolve("sub/a.txt"), "needle");
        Files.writeString(tmp.resolve("sub/deep/b.txt"), "needle");
        Files.writeString(tmp.resolve("other/c.txt"), "needle");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.of("sub/**/*.txt"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(textOf(result).split("\n")).containsExactlyInAnyOrder(
            "sub/a.txt:1: needle", "sub/deep/b.txt:1: needle");
    }

    @Test
    void gitignoreRulesAreRespected() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "ignored.txt\nbuild/\n");
        Files.writeString(tmp.resolve("ignored.txt"), "needle");
        Files.writeString(tmp.resolve("kept.txt"), "needle");
        Files.createDirectories(tmp.resolve("build"));
        Files.writeString(tmp.resolve("build/out.txt"), "needle");

        var result = grep(input("needle"));

        assertThat(textOf(result)).isEqualTo("kept.txt:1: needle");
    }

    @Test
    void dotGitDirectoryIsNeverSearched() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        Files.writeString(tmp.resolve(".git/config"), "needle");
        Files.writeString(tmp.resolve("real.txt"), "needle");

        var result = grep(input("needle"));

        assertThat(textOf(result)).isEqualTo("real.txt:1: needle");
    }

    @Test
    void dotfilesAreSearchedLikeRipgrepHidden() throws Exception {
        Files.writeString(tmp.resolve(".hidden"), "needle");

        var result = grep(input("needle"));

        assertThat(textOf(result)).isEqualTo(".hidden:1: needle");
    }

    @Test
    void binaryFilesAreSkippedByNulSniff() throws Exception {
        var bytes = new byte[256];
        System.arraycopy("needle".getBytes(), 0, bytes, 100, 6);
        bytes[10] = 0;
        Files.write(tmp.resolve("bin.dat"), bytes);

        var result = grep(input("needle"));

        assertThat(textOf(result)).isEqualTo("No matches found");
    }

    // ── 告示与 details ─────────────────────────────────────────────

    @Test
    void matchLimitProducesVerbatimNoticeAndDetails() throws Exception {
        Files.writeString(tmp.resolve("f.txt"),
            "needle a\nneedle b\nneedle c");

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(2)));

        var text = textOf(result);
        assertThat(text).endsWith(
            "\n\n[2 matches limit reached. Use limit=4 for more, or refine pattern]");
        assertThat(text).startsWith("f.txt:1: needle a\nf.txt:2: needle b");
        assertThat(result.details().matchLimitReached()).isEqualTo(2);
        assertThat(result.details().truncation()).isNull();
    }

    @Test
    void longLinesAreTruncatedWithNoticeAndDetails() throws Exception {
        Files.writeString(tmp.resolve("f.txt"), "needle " + "x".repeat(600));

        var result = grep(input("needle"));

        var text = textOf(result);
        assertThat(text).contains("needle " + "x".repeat(493) + "... [truncated]");
        assertThat(text).endsWith(
            "\n\n[Some lines truncated to 500 chars. Use read tool to see full lines]");
        assertThat(result.details().linesTruncated()).isTrue();
    }

    @Test
    void byteLimitProducesVerbatimNoticeAndDetails() throws Exception {
        // 200 行 × ~411B ≈ 82KB > 50KB；行均 <500 字不触发 linesTruncated；
        // limit=500 > 200 不触发 match limit。
        Files.writeString(tmp.resolve("big.txt"),
            ("needle " + "y".repeat(400) + "\n").repeat(200));

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(500)));

        var text = textOf(result);
        assertThat(text).endsWith("\n\n[50.0KB limit reached]");
        assertThat(result.details().truncation()).isNotNull();
        assertThat(result.details().truncation().truncated()).isTrue();
        assertThat(result.details().matchLimitReached()).isNull();
        assertThat(result.details().linesTruncated()).isNull();
    }

    @Test
    void noticeOrderIsMatchLimitThenByteLimit() throws Exception {
        Files.writeString(tmp.resolve("big.txt"),
            ("needle " + "y".repeat(400) + "\n").repeat(300));

        var result = grep(new GrepTool.GrepInput("needle",
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(300)));

        assertThat(textOf(result)).endsWith(
            "\n\n[300 matches limit reached. Use limit=600 for more, or refine pattern."
                + " 50.0KB limit reached]");
    }

    // ── helpers ───────────────────────────────────────────────────

    private GrepTool.GrepInput input(String pattern) {
        return new GrepTool.GrepInput(pattern, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private ToolResult<GrepTool.GrepDetails> grep(GrepTool.GrepInput params) throws Exception {
        return GrepTool.create().execute("id", params, null, null, TestContexts.at(tmp));
    }

    private static String textOf(ToolResult<GrepTool.GrepDetails> result) {
        return ((ContentBlock.TextContent) result.content().get(0)).text();
    }
}
