package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** docs/54 §2.1：minimal-mode 验收——read 行区间后缀（改动点）、shortenPath、渲染文本。 */
class MinimalModeExtensionTest {

    @Test
    void registration() {
        var w = new W3Context();
        new MinimalModeExtension().register(w.ctx);
        assertThat(w.rendererCount).isEqualTo(1);
    }

    @Test
    void readLineSuffix() {
        assertThat(MinimalModeExtension.readLineSuffix(null, null)).isEmpty();
        assertThat(MinimalModeExtension.readLineSuffix(5, null))
            .isEqualTo(":5");
        assertThat(MinimalModeExtension.readLineSuffix(5, 14))
            .isEqualTo(":5-18");
    }

    @Test
    void shortenPath() {
        var home = System.getProperty("user.home");
        assertThat(MinimalModeExtension.shortenPath(home + "/project/a.java"))
            .startsWith("~/").endsWith("/project/a.java");
        assertThat(MinimalModeExtension.shortenPath("C:\\other\\file.txt"))
            .isEqualTo("C:/other/file.txt");
    }

    @Test
    void renderCallTexts() {
        var read = new MinimalModeExtension.MinimalRenderer("read");
        assertThat(read.renderCall("read", Map.of("path", "a.java", "offset", 5, "limit", 14), false))
            .containsExactly("read a.java:5-18");

        var bash = new MinimalModeExtension.MinimalRenderer("bash");
        assertThat(bash.renderCall("bash", Map.of("command", "ls", "timeout", 3), false))
            .containsExactly("$ ls (timeout 3s)");

        var grep = new MinimalModeExtension.MinimalRenderer("grep");
        assertThat(grep.renderCall("grep", Map.of("pattern", "foo", "path", "src", "glob", "*.java"), false))
            .containsExactly("grep /foo/ in src (*.java)");
    }

    @Test
    void renderResultCollapsed() {
        var find = new MinimalModeExtension.MinimalRenderer("find");
        var result = find.renderResult("find", "a\nb\nc", false, false);
        assertThat(result).containsExactly("→ 3 files");
    }
}
