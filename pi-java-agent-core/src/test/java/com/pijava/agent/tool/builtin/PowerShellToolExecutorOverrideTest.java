package com.pijava.agent.tool.builtin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.ShellExecutor;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;

/**
 * B286：powershell 工厂的执行后端覆盖（pi {@code PowerShellToolOptions.operations}）——
 * 与 bash 同一 {@link ShellExecutor} 缝；覆盖后不再触达平台门。
 */
class PowerShellToolExecutorOverrideTest {

    @TempDir Path tmp;

    @Test
    void overrideWinsAndSkipsThePlatformGate() throws Exception {
        var seen = new AtomicReference<String>();
        ShellExecutor override = (command, options) -> {
            seen.set(command);
            return new ShellResult("ps-out", 0, false, false, 1, 6);
        };

        var result = PowerShellTool.create(true, override).execute("id",
            new BashTool.BashInput("Get-Date", Optional.empty()), null, null,
            contextThatMustNotRun());

        // pi :35：覆盖执行器也收到经 exec 包装后的命令（UTF8 前缀；5.1 的退出码后缀随解析结果）。
        assertThat(seen.get()).isEqualTo(PowerShellTool.wrapCommand("Get-Date"));
        assertThat(((ContentBlock.TextContent) result.content().get(0)).text())
            .isEqualTo("ps-out");
    }

    private ToolContext contextThatMustNotRun() {
        ShellExecutor mustNotRun = (command, options) -> {
            throw new AssertionError("context.shell() must not be used: " + command);
        };
        return new ToolContext(tmp.toString(), Map.of(), mustNotRun, new DefaultFileSystem());
    }
}
