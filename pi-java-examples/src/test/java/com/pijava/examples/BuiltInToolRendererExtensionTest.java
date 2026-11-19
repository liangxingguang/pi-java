package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** docs/54 §2.5：built-in-tool-renderer 验收——命令截断、diff 计数、exit code、渲染注册。 */
class BuiltInToolRendererExtensionTest {

    @Test
    void registration() {
        var w = new W3Context();
        new BuiltInToolRendererExtension().register(w.ctx);
        assertThat(w.rendererCount).isEqualTo(1);
    }

    @Test
    void truncateCommand80() {
        assertThat(BuiltInToolRendererExtension.truncateCommand("echo hi"))
            .isEqualTo("echo hi");
        var longCmd = "x".repeat(100);
        assertThat(BuiltInToolRendererExtension.truncateCommand(longCmd))
            .isEqualTo("x".repeat(77) + "...");
    }

    @Test
    void diffCounts() {
        var diff = "--- a\n+++ b\n+new line\n-removed\n unchanged\n+another";
        assertThat(BuiltInToolRendererExtension.diffCounts(diff)).isEqualTo("+2 / -1");
    }

    @Test
    void exitStatus() {
        assertThat(BuiltInToolRendererExtension.exitStatus("exit code: 0")).isEqualTo("done");
        assertThat(BuiltInToolRendererExtension.exitStatus("exit code: 3")).isEqualTo("exit 3");
        assertThat(BuiltInToolRendererExtension.exitStatus("no code")).isEqualTo("done");
    }

    @Test
    void renderCallAndResult() {
        var renderer = new BuiltInToolRendererExtension.PlainRenderer("bash");
        assertThat(renderer.renderCall("bash", Map.of("command", "echo hi"), false))
            .containsExactly("$ echo hi");
        assertThat(renderer.renderResult("bash", "exit code: 5", false, false))
            .contains("exit 5");

        var write = new BuiltInToolRendererExtension.PlainRenderer("write");
        assertThat(write.renderCall("write", Map.of("path", "a.txt", "content", "l1\nl2"), false))
            .containsExactly("write a.txt (2 lines)");
    }
}
