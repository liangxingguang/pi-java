package com.pijava.agent.tool.builtin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.ShellExecutor;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;

/**
 * B286：bash 工厂的执行后端覆盖（pi {@code BashToolOptions.operations}，
 * bash.ts:214-225）——扩展注册同名 {@code bash} 时用它接入 Docker/SSH 后端。
 */
class BashToolExecutorOverrideTest {

    @TempDir Path tmp;

    @Test
    void overrideExecutorWinsOverContextShell() throws Exception {
        var seen = new AtomicReference<String>();
        ShellExecutor override = (command, options) -> {
            seen.set(command);
            return new ShellResult("from-override", 0, false, false, 1, 13);
        };

        var result = BashTool.create(null, true, override).execute("id",
            new BashTool.BashInput("docker ps", Optional.empty()), null, null,
            contextThatMustNotRun());

        assertThat(seen.get()).isEqualTo("docker ps");
        assertThat(textOf(result)).isEqualTo("from-override");
    }

    @Test
    void nullOverrideStillUsesContextShell() throws Exception {
        var seen = new AtomicReference<String>();
        var context = new ToolContext(tmp.toString(), Map.of(),
            (command, options) -> {
                seen.set(command);
                return new ShellResult("local", 0, false, false, 1, 5);
            }, new DefaultFileSystem());

        var result = BashTool.create(null, true, null).execute("id",
            new BashTool.BashInput("ls", Optional.empty()), null, null, context);

        assertThat(seen.get()).isEqualTo("ls");
        assertThat(textOf(result)).isEqualTo("local");
    }

    @Test
    void nonZeroExitFromOverrideResolvesIsError() throws Exception {
        ShellExecutor override =
            (command, options) -> new ShellResult("boom", 7, false, false, 1, 4);

        var result = BashTool.create(null, true, override).execute("id",
            new BashTool.BashInput("false", Optional.empty()), null, null,
            contextThatMustNotRun());

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).isEqualTo("boom\n\nCommand exited with code 7");
    }

    @Test
    void timeoutFromOverrideUsesTheSameMessage() {
        ShellExecutor override =
            (command, options) -> new ShellResult("partial", 0, true, false, 1, 7);

        assertThatThrownBy(() -> BashTool.create(null, true, override).execute("id",
                new BashTool.BashInput("sleep 99", Optional.of(0.5)), null, null,
                contextThatMustNotRun()))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("partial\n\nCommand timed out after 0.5 seconds");
    }

    @Test
    void outputSinkIsForwardedToTheOverride() throws Exception {
        ShellExecutor override = (command, options) -> {
            options.outputSink().onOutput("streamed");
            return new ShellResult("streamed", 0, false, false, 1, 8);
        };
        var updates = new CopyOnWriteArrayList<ToolResult<BashTool.BashDetails>>();

        BashTool.create(null, true, override).execute("id",
            new BashTool.BashInput("slow", Optional.empty()), null, updates::add,
            contextThatMustNotRun());

        assertThat(updates).isNotEmpty();
        assertThat(textOf(updates.get(updates.size() - 1))).contains("streamed");
    }

    /** 若覆盖失效、回落到 {@code context.shell()}，测试立即失败。 */
    private ToolContext contextThatMustNotRun() {
        ShellExecutor mustNotRun = (command, options) -> {
            throw new AssertionError("context.shell() must not be used: " + command);
        };
        return new ToolContext(tmp.toString(), Map.of(), mustNotRun, new DefaultFileSystem());
    }

    private static String textOf(ToolResult<BashTool.BashDetails> result) {
        return ((ContentBlock.TextContent) result.content().get(0)).text();
    }
}
