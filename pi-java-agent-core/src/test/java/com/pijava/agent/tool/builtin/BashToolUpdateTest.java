package com.pijava.agent.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.builtin.BashTool.BashDetails;
import com.pijava.agent.tool.builtin.BashTool.BashInput;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑧（docs/35）：**真实** {@code BashTool} 跑一条命令时，部分结果确实流出去了。
 *
 * <p>这是 B46 的**决定性**夹具。发射器单测（{@code BashUpdateEmitterTest}）只证明
 * 「给它喂增量它会照 pi 的语义发」；本条证明「生产路径**真的**会喂它」——
 * 也就是「{@code tool_execution_update} 在生产上永不发射」这个缺口被填上了。</p>
 *
 * <p>⚠️ 用假 shell（{@code DefaultShellExecutor} + 自定义 shellPath）而不是真 bash：
 * 逐字沿用 {@code DefaultShellExecutorTest} 的手法，避免依赖宿主装了什么。</p>
 */
class BashToolUpdateTest {

    @TempDir
    Path tmp;

    private static final BashInput ANY_COMMAND =
        new BashInput("anything", Optional.empty());

    private static String textOf(ToolResult<BashDetails> update) {
        if (update.content().isEmpty()) {
            return "";
        }
        return ((ContentBlock.TextContent) update.content().get(0)).text();
    }

    private ToolContext contextWithFakeShell(int lines) throws Exception {
        var script = tmp.resolve("bash.cmd");
        Files.writeString(script, "@echo off\r\n"
            + "for /L %%i in (1,1," + lines + ") do @echo line %%i\r\n");
        return new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(script.toString()), new DefaultFileSystem());
    }

    @Test
    void bashEmitsUpdatesWhileRunningAndTheyAreCumulative() throws Exception {
        var updates = new CopyOnWriteArrayList<ToolResult<BashDetails>>();
        var tool = BashTool.create();

        var result = tool.execute("call_1", ANY_COMMAND,
            AbortSignal.create(), updates::add, contextWithFakeShell(1000));

        assertThat(updates).as("起手一条空载荷 ＋ 至少一次真实输出")
            .hasSizeGreaterThanOrEqualTo(2);

        // ④ 起手那条：content 是**空数组**、details 为 null（pi bash.ts:296-298）。
        assertThat(updates.get(0).content()).isEmpty();
        assertThat(updates.get(0).details()).isNull();

        // ⑤ 载荷是**累积快照**：长度单调不减（本例未触发截断）。
        var lengths = updates.stream().map(u -> textOf(u).length()).toList();
        assertThat(lengths).isSorted();

        // 最后一条更新与终局结果同源（都是同一份输出的截尾）。
        var finalText = ((ContentBlock.TextContent) result.content().get(0)).text();
        assertThat(textOf(updates.get(updates.size() - 1)))
            .as("最后一次更新必须已含终局输出的尾部")
            .contains("line 1000");
        assertThat(finalText).contains("line 1000");
    }

    @Test
    void nullUpdateCallbackLeavesThePlainPathUntouched() throws Exception {
        // 反向：onUpdate 为 null 时（pi 的 `if (onUpdate)` 等价物）不得炸。
        var tool = BashTool.create();

        var result = tool.execute("call_1", ANY_COMMAND,
            AbortSignal.create(), null, contextWithFakeShell(3));

        assertThat(result.content()).isNotEmpty();
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
            .contains("line 3");
    }
}
