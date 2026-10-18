package com.pijava.agent.tool.builtin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.ShellExecutor;
import com.pijava.agent.tool.ShellResult;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolSetFactory;

/**
 * B243：pi {@code powershell.ts} 对位夹具 —— 六面差异逐字、UTF8 前缀、
 * 注册≠激活（裁决③，docs/09 §13.3）。
 */
class PowerShellToolTest {

    @TempDir Path tmp;

    // ── 六面差异（pi powershellToolConfig :38-46 + snippet :19）─────

    @Test
    void identityFacesAreVerbatim() {
        var tool = PowerShellTool.create();
        assertThat(tool.name()).isEqualTo("powershell");
        assertThat(tool.label()).isEqualTo("powershell");
        assertThat(tool.promptSnippet()).isEqualTo("Execute PowerShell commands");
        assertThat(tool.description()).startsWith(
            "Execute a PowerShell command in the current working directory.");
        // pi bash.ts:47 的同文 guideline（exposeSessionEnvironment 同闸门控）。
        assertThat(tool.promptGuidelines()).containsExactly(
            "You can inspect PI_* environment variables for current model and session details.");
        assertThat(PowerShellTool.create(false).promptGuidelines()).isEmpty();
    }

    @Test
    void schemaIsTheSharedBashSchema() {
        var tool = PowerShellTool.create();
        // pi：bashSchema 两工具复用（powershell.ts 无自己的 schema）。
        assertThat(tool.inputSchema()).isEqualTo(BashTool.create().inputSchema());
    }

    @Test
    void utf8PrefixIsPrependedToTheCommand() {
        // pi :16,:35：exec 前置 UTF8 输出前缀（bash 不拼）。
        assertThat(PowerShellTool.UTF8_OUTPUT_PREFIX).isEqualTo(
            "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}\n");
        assertThat(PowerShellTool.wrapCommand("Get-Location")).isEqualTo(
            PowerShellTool.UTF8_OUTPUT_PREFIX + "Get-Location");
    }

    // ── 平台门（pi shell.ts:126-131 错文逐字）─────────────────────

    @Test
    void nonWindowsExecutionThrowsTheVerbatimGate() {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        var tool = PowerShellTool.create();
        var context = new ToolContext(tmp.toString(), Map.of(),
            (ShellExecutor) (command, options) -> new ShellResult("", 0, false, false, 0, 0),
            new DefaultFileSystem());
        try {
            tool.execute("id", new BashTool.BashInput("Get-Location", Optional.empty()),
                null, null, context);
            org.assertj.core.api.Assertions.fail("should throw the platform gate");
        } catch (Exception e) {
            assertThat(e).hasMessage("The powershell tool is only available on Windows.");
        }
    }

    // ── 注册≠激活（裁决③）────────────────────────────────────────

    @Test
    void createAllToolsRegistersEightInPiOrder() {
        // pi index.ts:96-105 allToolNames 序：read, bash, powershell, edit, write, grep, find, ls。
        var names = ToolSetFactory.createAllTools(null).stream().map(t -> t.name()).toList();
        assertThat(names).containsExactly(
            "read", "bash", "powershell", "edit", "write", "grep", "find", "ls");
    }

    @Test
    void defaultCodingToolsStayAtSevenWithoutPowerShell() {
        // pi DEFAULT_TOOL_NAMES 恒四件、createAllTools 注册八件 —— 默认编码集不含
        // powershell（Windows 裸跑也不激活，裁决③）。
        var names = ToolSetFactory.createCodingTools(null).stream().map(t -> t.name()).toList();
        assertThat(names).containsExactly("read", "bash", "edit", "write", "grep", "find", "ls");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
