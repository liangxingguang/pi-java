package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
 * 批 3/docs/22 §3：find（原 glob）模型可见契约逐字——fd 语义（basename vs full-path
 * ＋ {@code **&#47;} 前缀适配）、目录入选、limit/gitignore、告示与 details。
 */
class FindToolTest {

    @TempDir Path tmp;

    // ── 契约面 ────────────────────────────────────────────────────

    @Test
    void nameAndLabelAreFind() {
        var tool = FindTool.create();
        assertThat(tool.name()).isEqualTo("find");
        assertThat(tool.label()).isEqualTo("find");
    }

    @Test
    void descriptionIsPiVerbatim() {
        assertThat(FindTool.create().description()).isEqualTo(
            "Search for files by glob pattern. Returns matching file paths relative to "
                + "the search directory. Respects .gitignore. Output is truncated to "
                + "1000 results or 50KB (whichever is hit first).");
    }

    @Test
    void promptSnippetIsPiVerbatim() {
        assertThat(FindTool.create().promptSnippet())
            .isEqualTo("Find files by glob pattern (respects .gitignore)");
    }

    @Test
    void schemaIsPiVerbatim() {
        var schema = FindTool.create().inputSchema();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).containsExactly("pattern");
        @SuppressWarnings("unchecked")
        var p = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(p.keySet()).containsExactlyInAnyOrder("pattern", "path", "limit");
        assertThat(p.get("pattern").get("description")).isEqualTo(
            "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'");
        assertThat(p.get("path").get("description"))
            .isEqualTo("Directory to search in (default: current directory)");
        assertThat(p.get("limit").get("description"))
            .isEqualTo("Maximum number of results (default: 1000)");
    }

    // ── fd 语义 ───────────────────────────────────────────────────

    @Test
    void basenamePatternMatchesAtAnyDepth() throws Exception {
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/x.txt"), "a");
        Files.writeString(tmp.resolve("y.md"), "b");

        var result = find(input("*.txt"));

        assertThat(textOf(result)).isEqualTo("sub/x.txt");
    }

    @Test
    void directoriesAreIncludedInResults() throws Exception {
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub/file.txt"), "a");

        var result = find(input("sub"));

        assertThat(textOf(result)).isEqualTo("sub");
    }

    @Test
    void slashPatternGetsStarStarPrefixLikeFdFullPath() throws Exception {
        // fd --full-path ⇒ 'sub/*.txt' 变 '**/sub/*.txt'，任意深度都中。
        Files.createDirectories(tmp.resolve("sub"));
        Files.createDirectories(tmp.resolve("deep/sub"));
        Files.writeString(tmp.resolve("sub/a.txt"), "a");
        Files.writeString(tmp.resolve("deep/sub/b.txt"), "b");
        Files.writeString(tmp.resolve("z.txt"), "c");

        var result = find(input("sub/*.txt"));

        assertThat(linesOf(result))
            .containsExactlyInAnyOrder("sub/a.txt", "deep/sub/b.txt");
    }

    @Test
    void doubleStarSlashPatternIsUsedAsIs() throws Exception {
        Files.createDirectories(tmp.resolve("a/b"));
        Files.writeString(tmp.resolve("a/b/c.md"), "x");
        Files.writeString(tmp.resolve("top.md"), "y");

        var result = find(input("**/*.md"));

        assertThat(linesOf(result))
            .containsExactlyInAnyOrder("a/b/c.md", "top.md");
    }

    @Test
    void gitignoreRulesAreRespected() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "ignored.txt\nbuild/\n");
        Files.writeString(tmp.resolve("ignored.txt"), "a");
        Files.writeString(tmp.resolve("kept.txt"), "b");
        Files.createDirectories(tmp.resolve("build"));
        Files.writeString(tmp.resolve("build/out.txt"), "c");

        var result = find(input("*.txt"));

        assertThat(textOf(result)).isEqualTo("kept.txt");
    }

    @Test
    void dotGitDirectoryIsNeverSearched() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        Files.writeString(tmp.resolve(".git/config.txt"), "a");
        Files.writeString(tmp.resolve("real.txt"), "b");

        var result = find(input("*.txt"));

        assertThat(textOf(result)).isEqualTo("real.txt");
    }

    @Test
    void dotfilesAreIncludedLikeFdHidden() throws Exception {
        Files.writeString(tmp.resolve(".hidden.txt"), "a");

        var result = find(input("*.txt"));

        assertThat(textOf(result)).isEqualTo(".hidden.txt");
    }

    // ── limit 与告示 ───────────────────────────────────────────────

    @Test
    void resultLimitProducesVerbatimNoticeAndDetails() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "1");
        Files.writeString(tmp.resolve("b.txt"), "2");
        Files.writeString(tmp.resolve("c.txt"), "3");

        var result = find(new FindTool.FindInput("*.txt", Optional.empty(), Optional.of(2)));

        var text = textOf(result);
        assertThat(text).endsWith(
            "\n\n[2 results limit reached. Use limit=4 for more, or refine pattern]");
        assertThat(linesOf(result)).hasSize(2);
        assertThat(result.details().resultLimitReached()).isEqualTo(2);
        assertThat(result.details().truncation()).isNull();
    }

    @Test
    void byteLimitProducesVerbatimNoticeAndDetails() throws Exception {
        // 300 个 180 字长名文件 ≈ 55KB > 50KB；limit 默认 1000 不触发 results 告示。
        String name = "n".repeat(180) + ".txt";
        for (int i = 0; i < 300; i++) {
            Files.writeString(tmp.resolve(i + name), "x");
        }

        var result = find(input("*.txt"));

        assertThat(textOf(result)).endsWith("\n\n[50.0KB limit reached]");
        assertThat(result.details().truncation()).isNotNull();
        assertThat(result.details().truncation().truncated()).isTrue();
        assertThat(result.details().resultLimitReached()).isNull();
    }

    @Test
    void limitZeroYieldsEmptyResultMessage() throws Exception {
        // pi find.ts:112 无 max(1,…)；fd --max-results 0 ⇒ 无输出 ⇒ 空结果文案。
        Files.writeString(tmp.resolve("a.txt"), "1");

        var result = find(new FindTool.FindInput("*.txt", Optional.empty(), Optional.of(0)));

        assertThat(textOf(result)).isEqualTo("No files found matching pattern");
        assertThat(result.details()).isNull();
    }

    // ── 终局 ──────────────────────────────────────────────────────

    @Test
    void noMatchReturnsVerbatimMessage() throws Exception {
        Files.writeString(tmp.resolve("a.md"), "x");

        var result = find(input("*.txt"));

        assertThat(textOf(result)).isEqualTo("No files found matching pattern");
        assertThat(result.details()).isNull();
    }

    @Test
    void pathNotFoundThrowsVerbatim() {
        String missing = Path.of(tmp.toString(), "missing").toString();
        assertThatThrownBy(() -> find(new FindTool.FindInput("*.txt",
                Optional.of("missing"), Optional.empty())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Path not found: " + missing);
    }

    @Test
    void detailsAreNullWhenNothingWasTruncated() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "x");

        var result = find(input("*.txt"));

        assertThat(result.details()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────

    private FindTool.FindInput input(String pattern) {
        return new FindTool.FindInput(pattern, Optional.empty(), Optional.empty());
    }

    private ToolResult<FindTool.FindDetails> find(FindTool.FindInput params) throws Exception {
        return FindTool.create().execute("id", params, null, null, TestContexts.at(tmp));
    }

    private static String textOf(ToolResult<FindTool.FindDetails> result) {
        return ((ContentBlock.TextContent) result.content().get(0)).text();
    }

    /** 结果行（不含尾部告示段）；fd 遍历序不保证（D7），用集合断言。 */
    private static java.util.List<String> linesOf(ToolResult<FindTool.FindDetails> result) {
        String text = textOf(result);
        int noticeIdx = text.indexOf("\n\n[");
        if (noticeIdx >= 0) {
            text = text.substring(0, noticeIdx);
        }
        return Arrays.asList(text.split("\n"));
    }
}
