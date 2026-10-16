package com.pijava.agent.tool.builtin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ShellOptions;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;

class BashToolTest {

    @Test
    void descriptionAlwaysPromisesRealBash() {
        var tool = BashTool.create();
        var os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertThat(tool.description())
                .as("Windows hosts run the command in Git Bash")
                .contains("Git Bash")
                .contains("ls -la")
                .contains("No bash shell found");
        } else {
            assertThat(tool.description())
                .as("POSIX hosts run the command in bash")
                .contains("bash");
        }
    }

    @Test
    void descriptionPromisesPi50KBBudget() {
        // B158：pi bash.ts:255 的描述由 DEFAULT_MAX_BYTES 现算 —— 50KB（旧值是 97KB）。
        assertThat(BashTool.create().description()).contains("50KB").doesNotContain("97KB");
    }

    @Test
    void truncationNoteQuotesThe50KBBudget() throws Exception {
        // 多行、总字节 >51200 ⇒ 尾窗注记「(50.0KB limit). Full output saved to: …」。
        var big = ("x".repeat(100) + "\n").repeat(600);   // 600 行 ×101B ≈ 60.6KB
        var tool = BashTool.create();
        var context = new ToolContext(
            System.getProperty("user.dir"), java.util.Map.of(),
            (command, options) -> new ShellResult(big, 0, false, false, 600, big.length()),
            new DefaultFileSystem());

        var result = tool.execute("id", new BashTool.BashInput("big", java.util.Optional.empty()),
            null, null, context);

        var text = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(text).contains("(50.0KB limit). Full output saved to:");
    }

    @Test
    void schemaRequiresCommand() {
        var schema = BashTool.create().inputSchema();
        assertThat(schema.get("required")).isNotNull();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) schema.get("required");
        assertThat(required).contains("command");
    }

    @Test
    void missingTimeoutDefaultsToTwoMinutes() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<ShellOptions>();
        var tool = BashTool.create();
        var context = new ToolContext(
            System.getProperty("user.dir"),
            java.util.Map.of(),
            (command, options) -> {
                captured.set(options);
                return new ShellResult("ok", 0, false, false, 1, 2);
            },
            new DefaultFileSystem());

        tool.execute("id", new BashTool.BashInput("echo hi", java.util.Optional.empty()),
            null, null, context);

        assertThat(captured.get().timeoutSeconds()).hasValue(120L);
    }

    @Test
    void explicitTimeoutIsPassedThrough() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<ShellOptions>();
        var tool = BashTool.create();
        var context = new ToolContext(
            System.getProperty("user.dir"),
            java.util.Map.of(),
            (command, options) -> {
                captured.set(options);
                return new ShellResult("ok", 0, false, false, 1, 2);
            },
            new DefaultFileSystem());

        tool.execute("id", new BashTool.BashInput("echo hi", java.util.Optional.of(300L)),
            null, null, context);

        assertThat(captured.get().timeoutSeconds()).hasValue(300L);
    }

    @Test
    void timeoutAboveMaximumIsRejected() throws Exception {
        var tool = BashTool.create();
        var context = new ToolContext(
            System.getProperty("user.dir"),
            java.util.Map.of(),
            (command, options) -> new ShellResult("", 0, false, false, 0, 0),
            new DefaultFileSystem());

        var params = new BashTool.BashInput("echo hi", java.util.Optional.of(601L));

        assertThatThrownBy(() -> tool.execute("id", params, null, null, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximum of 600");
    }
    @Test
    void rawArgumentsFallbackRecoversCommand() {
        var tool = BashTool.create();

        var nested = tool.prepareArguments(java.util.Map.of("_raw",
            "{\"command\": \"echo hi\"}"));
        assertThat(nested.command()).isEqualTo("echo hi");

        var truncated = tool.prepareArguments(java.util.Map.of("_raw",
            "{\"command\": \"echo \\\"hi\\\" --max-time 15\", \"timeout\": 30"));
        assertThat(truncated.command()).isEqualTo("echo \"hi\" --max-time 15");
    }
}
