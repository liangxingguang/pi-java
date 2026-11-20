package com.pijava.coding.agent.core.render;

import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.extension.ToolCallRenderer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/60（L7 W1b）：内置渲染器的文本形状逐字对位 pi
 * {@code core/tools/renderers/*}。
 */
class BuiltInRenderersTest {

    private static List<String> call(String tool, Map<String, Object> args) {
        return BuiltInRenderers.resolve(tool).renderCall(tool, args, false);
    }

    private static List<String> result(String tool, String text, boolean isError, boolean expanded) {
        return BuiltInRenderers.resolve(tool).renderResult(tool, text, isError, expanded);
    }

    @Test
    void everyBuiltInToolResolvesAndOthersDoNot() {
        for (var name : List.of("read", "bash", "powershell", "edit", "write", "grep", "find", "ls")) {
            assertThat(BuiltInRenderers.resolve(name)).as(name).isNotNull();
        }
        assertThat(BuiltInRenderers.resolve("custom_tool")).isNull();
        assertThat(BuiltInRenderers.resolve(null)).isNull();
    }

    // ── read ────────────────────────────────────────────────

    @Test
    void readCallShowsPathAndLineRange() {
        assertThat(call("read", Map.of("path", "src/A.java"))).containsExactly("read src/A.java");
        assertThat(call("read", Map.of("path", "src/A.java", "offset", 5, "limit", 10)))
            .containsExactly("read src/A.java:5-14");
        // 数字字符串（模型常这么传）经 ReadLineRange 归一。
        assertThat(call("read", Map.of("path", "src/A.java", "offset", "5")))
            .containsExactly("read src/A.java:5");
    }

    @Test
    void readCallFallsBackToEllipsisAndInvalidArg() {
        assertThat(call("read", Map.of())).containsExactly("read ...");
        // 屏上仍是 `[invalid arg]`：方括号按 markup 规则转义成 `[[…]]`。
        assertThat(call("read", Map.of("path", 42))).containsExactly("read [[invalid arg]]");
    }

    @Test
    void readCallEscapesMarkupInPaths() {
        assertThat(call("read", Map.of("path", "a[0].txt"))).containsExactly("read a[[0]].txt");
    }

    @Test
    void readResultIsHiddenWhileCollapsedUnlessItIsAnError() {
        assertThat(result("read", "line", false, false)).isEmpty();

        var twelve = String.join("\n", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"));
        var folded = result("read", twelve, true, false);
        assertThat(folded).hasSize(11);
        assertThat(folded.get(10)).isEqualTo("... (2 more lines, ctrl+o to expand)");

        assertThat(result("read", twelve, false, true)).hasSize(12);
    }

    // ── bash / powershell ───────────────────────────────────

    @Test
    void shellCallShowsPromptCommandAndTimeout() {
        assertThat(call("bash", Map.of("command", "npm test"))).containsExactly("$ npm test");
        assertThat(call("bash", Map.of("command", "npm test", "timeout", 30)))
            .containsExactly("$ npm test (timeout 30s)");
        assertThat(call("powershell", Map.of("command", "Get-ChildItem")))
            .containsExactly("PS> Get-ChildItem");
    }

    @Test
    void shellCallHandlesMissingAndInvalidCommands() {
        assertThat(call("bash", Map.of())).containsExactly("$ ...");
        assertThat(call("bash", Map.of("command", 42))).containsExactly("$ [[invalid arg]]");
    }

    @Test
    void shellResultKeepsTheTailWhileCollapsed() {
        var eight = String.join("\n", List.of("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8"));

        var folded = result("bash", eight, false, false);
        assertThat(folded).hasSize(6);
        assertThat(folded.get(0)).isEqualTo("... (3 earlier lines, ctrl+o to expand)");
        assertThat(folded.subList(1, 6)).containsExactly("l4", "l5", "l6", "l7", "l8");

        assertThat(result("bash", eight, false, true)).hasSize(8);
    }

    // ── write ───────────────────────────────────────────────

    @Test
    void writeCallShowsHeaderAndContentPreview() {
        assertThat(call("write", Map.of("path", "a.txt", "content", "one\ntwo")))
            .containsExactly("write a.txt", "", "one", "two");
        assertThat(call("write", Map.of("path", "a.txt", "content", "")))
            .containsExactly("write a.txt");
    }

    @Test
    void writeCallFoldsLongContentWithTheTotalCount() {
        var twelve = String.join("\n", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"));

        var lines = call("write", Map.of("path", "a.txt", "content", twelve));

        assertThat(lines).hasSize(13);
        assertThat(lines.get(12)).isEqualTo("... (2 more lines, 12 total, ctrl+o to expand)");
    }

    @Test
    void writeCallRejectsNonStringContent() {
        assertThat(call("write", Map.of("path", "a.txt", "content", 5)))
            .containsExactly("write a.txt", "", "[[invalid content arg - expected string]]");
    }

    @Test
    void writeResultOnlySpeaksOnError() {
        assertThat(result("write", "done", false, false)).isEmpty();
        assertThat(result("write", "boom", true, false)).containsExactly("boom");
    }

    // ── grep / find / ls ────────────────────────────────────

    @Test
    void grepCallShowsPatternPathGlobAndBareLimit() {
        assertThat(call("grep", Map.of("pattern", "TODO", "path", "src",
                "glob", "*.java", "limit", 5)))
            .containsExactly("grep /TODO/ in src (*.java) limit 5");
        assertThat(call("grep", Map.of())).containsExactly("grep // in .");
    }

    @Test
    void grepResultFoldsAtFifteenLines() {
        var twenty = lines(20);

        var folded = result("grep", twenty, false, false);

        assertThat(folded).hasSize(16);
        assertThat(folded.get(15)).isEqualTo("... (5 more lines, ctrl+o to expand)");
    }

    @Test
    void findCallUsesParenthesisedLimit() {
        assertThat(call("find", Map.of("pattern", "*.java", "path", "src", "limit", 10)))
            .containsExactly("find *.java in src (limit 10)");
    }

    @Test
    void lsCallDefaultsToDotAndFoldsAtTwentyLines() {
        assertThat(call("ls", Map.of())).containsExactly("ls .");
        assertThat(call("ls", Map.of("path", "/tmp", "limit", 20)))
            .containsExactly("ls /tmp (limit 20)");
        assertThat(result("ls", lines(25), false, false)).hasSize(21);
    }

    @Test
    void callRenderersSurviveNullArguments() {
        for (var name : List.of("read", "bash", "powershell", "edit", "write", "grep", "find", "ls")) {
            assertThat(BuiltInRenderers.resolve(name).renderCall(name, null, false)).as(name).isNotNull();
        }
    }

    // ── edit ────────────────────────────────────────────────

    @Test
    void editKeepsTheDefaultDiffRendering() {
        assertThat(call("edit", Map.of("path", "a.txt"))).containsExactly("edit a.txt");

        ToolCallRenderer edit = BuiltInRenderers.resolve("edit");
        assertThat(edit.renderResult("edit", "diff", false, false)).isNull();
    }

    private static String lines(int count) {
        var out = new java.util.ArrayList<String>(count);
        for (int i = 1; i <= count; i++) {
            out.add("l" + i);
        }
        return String.join("\n", out);
    }
}
