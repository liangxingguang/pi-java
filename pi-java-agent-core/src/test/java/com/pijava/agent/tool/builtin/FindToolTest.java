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
            "Glob pattern relative to the search directory, e.g. 'src/**/*.ts'. "
                + "Without '/', e.g. '*.ts', it matches file names at any depth");
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
    void relativePathPatternIsAnchoredAtSearchRoot() throws Exception {
        // find.ts:240-250：'src/*.ts' 只中根下 src/，不中 pkg/src/（grep glob 同 #12066）。
        Files.createDirectories(tmp.resolve("src"));
        Files.createDirectories(tmp.resolve("pkg/src"));
        Files.writeString(tmp.resolve("src/a.ts"), "a");
        Files.writeString(tmp.resolve("pkg/src/b.ts"), "b");

        var result = find(input("src/*.ts"));

        assertThat(linesOf(result)).containsExactly("src/a.ts");
    }

    @Test
    void leadingDoubleStarPathPatternMatchesAnyDepth() throws Exception {
        // '**/src/*.ts' 同时中根下与嵌套的 src/。
        Files.createDirectories(tmp.resolve("src"));
        Files.createDirectories(tmp.resolve("pkg/src"));
        Files.writeString(tmp.resolve("src/a.ts"), "a");
        Files.writeString(tmp.resolve("pkg/src/b.ts"), "b");

        var result = find(input("**/src/*.ts"));

        assertThat(linesOf(result))
            .containsExactlyInAnyOrder("src/a.ts", "pkg/src/b.ts");
    }

    @Test
    void leadingDotSlashIsStripped() throws Exception {
        // fd 的 full path 不含 "./"，不剥就一条都中不了（find.ts:211）。
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/a.ts"), "a");

        var result = find(input("./src/*.ts"));

        assertThat(linesOf(result)).containsExactly("src/a.ts");
    }

    @Test
    void repeatedDotSlashPrefixesAreStripped() {
        assertThat(FindTool.normalizePattern("././src/*.ts", false)).isEqualTo("src/*.ts");
        assertThat(FindTool.normalizePattern("src/./a.ts", false)).isEqualTo("src/./a.ts");
    }

    @Test
    void backslashesNormalizeOnWindowsOnly() {
        // find.ts:209：Win32 上 '\' 是路径分隔符；POSIX 上 '\' 保持 glob 转义。
        assertThat(FindTool.normalizePattern("src\\*.ts", true)).isEqualTo("src/*.ts");
        assertThat(FindTool.normalizePattern("src\\*.ts", false)).isEqualTo("src\\*.ts");
    }

    @Test
    void allLowercasePatternMatchesCaseInsensitively() throws Exception {
        // fd smart case：全小写 pattern 忽略大小写。
        Files.writeString(tmp.resolve("A.TXT"), "a");

        var result = find(input("*.txt"));

        assertThat(textOf(result)).isEqualTo("A.TXT");
    }

    // smart case 的大小写敏感分支在大小写不敏感的文件系统上无法用文件验证，
    // 由 GlobPatternTest 直接在匹配器上钉住。

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
    void limitZeroClampsToOne() throws Exception {
        // find.ts:114：fd 把 --max-results 0 当无限，所以 1 以下都算 1。
        Files.writeString(tmp.resolve("a.txt"), "1");

        var result = find(new FindTool.FindInput("*.txt", Optional.empty(), Optional.of(0)));

        assertThat(textOf(result)).isEqualTo("a.txt");
        assertThat(result.details()).isNull();
    }

    @Test
    void exactlyLimitMatchesDoesNotReportLimitReached() throws Exception {
        // 恰好 N 条 ≠ 被截断：第 N+1 条候选出现才置 resultLimitReached（#7121）。
        Files.writeString(tmp.resolve("a.txt"), "1");
        Files.writeString(tmp.resolve("b.txt"), "2");
        Files.writeString(tmp.resolve("c.txt"), "3");

        var result = find(new FindTool.FindInput("*.txt", Optional.empty(), Optional.of(3)));

        assertThat(linesOf(result)).hasSize(3);
        assertThat(textOf(result)).doesNotContain("results limit reached");
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
