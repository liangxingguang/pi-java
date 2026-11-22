package com.pijava.agent.tool.builtin;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.PowerShellShellExecutor;
import com.pijava.agent.tool.ShellExecutor;

import org.jspecify.annotations.Nullable;

/**
 * PowerShell 工具（B243）—— pi {@code powershell.ts}（67 行）对位：与 bash 同骨架
 * （schema/截断/超时/PI_* 注入全共享 {@link ShellToolSupport}），差异六面逐字：
 *
 * <ul>
 *   <li>name/label/shellName/prompt/tempFilePrefix（{@code powershellToolConfig} :38-46）；</li>
 *   <li>snippet {@code "Execute PowerShell commands"}（:19）；</li>
 *   <li>exec 前置 {@link #UTF8_OUTPUT_PREFIX}（:16,:35）；</li>
 *   <li>执行器走 {@link PowerShellShellExecutor}（pwsh → powershell 解析，
 *       shell.ts:122-136；平台门在执行时不在注册时）；</li>
 *   <li>options 裁掉 commandPrefix/shellPath（:27-28 的 {@code Pick}）；</li>
 *   <li>注册≠激活：pi 全平台注册进 {@code allToolNames}，但
 *       {@code DEFAULT_TOOL_NAMES} 恒四件 —— 默认不激活（裁决③，docs/09 §13.3）。</li>
 * </ul>
 */
public final class PowerShellTool {

    /** pi powershell.ts:16 逐字。 */
    static final String UTF8_OUTPUT_PREFIX =
        "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}\n";

    /**
     * Windows PowerShell 5.1 turns redirected native stderr into error records and sets
     * {@code $?} false, so {@code git push 2>&1 | Select-Object -Last 3} exits 1 although git
     * exited 0 (#8842); PowerShell 7 does not. pi powershell.ts:19-26 逐字。
     */
    static final String WINDOWS_POWERSHELL_EXIT_SUFFIX =
        "\n;\nif ($?) { exit 0 }\nif (($LASTEXITCODE -eq 0) -and "
            + "(\"$($Error[0].FullyQualifiedErrorId)\" -like \"NativeCommandError*\")) { exit 0 }\nexit 1";

    /** pi {@code powershellToolConfig}（powershell.ts:38-46）逐字。 */
    static final ShellToolConfig POWERSHELL_CONFIG = new ShellToolConfig(
        "powershell", "powershell", "PowerShell", "PS>",
        "Execute PowerShell commands",
        BashTool.PI_GUIDELINES, "pi-powershell");

    private PowerShellTool() {}

    /** Create the powershell tool（pi {@code createPowerShellTool}；默认暴露 PI_*）。 */
    public static AgentTool<BashTool.BashInput, BashTool.BashDetails> create() {
        return create(true);
    }

    /** Create the powershell tool（pi {@code PowerShellToolOptions.exposeSessionEnvironment}）。 */
    public static AgentTool<BashTool.BashInput, BashTool.BashDetails> create(
            boolean exposeSessionEnvironment) {
        return create(exposeSessionEnvironment, new PowerShellShellExecutor());
    }

    /**
     * Create the powershell tool with an execution backend override（pi
     * {@code PowerShellToolOptions.operations}；与 bash 同一 {@link ShellExecutor} 缝）。
     *
     * @param executorOverride 执行后端；{@code null} ⇒ {@code context.shell()}
     */
    public static AgentTool<BashTool.BashInput, BashTool.BashDetails> create(
            boolean exposeSessionEnvironment, @Nullable ShellExecutor executorOverride) {
        return ShellToolSupport.create(POWERSHELL_CONFIG, null, exposeSessionEnvironment,
            PowerShellTool::wrapCommand, executorOverride);
    }

    /** pi :35/:46-52 的 exec 包装：UTF8 输出前缀 ＋ 视 shell 决定的退出码修正后缀。 */
    static String wrapCommand(String command) {
        String shellName;
        try {
            shellName = java.nio.file.Path.of(
                PowerShellShellExecutor.resolvePowerShell()).getFileName().toString();
        } catch (java.io.IOException e) {
            shellName = "";   // execute() reports the same error; do not fail while wrapping
        }
        return wrapCommand(command, shellName);
    }

    /**
     * The wrap with an explicit shell file name, for tests: only
     * {@code powershell.exe} gets the 5.1 exit-code suffix, and a final backtick keeps the
     * user's last statement from continuing into the suffix.
     */
    static String wrapCommand(String command, String shellFileName) {
        boolean addSuffix = "powershell.exe".equalsIgnoreCase(shellFileName)
            && !command.endsWith("`");
        return UTF8_OUTPUT_PREFIX + command + (addSuffix ? WINDOWS_POWERSHELL_EXIT_SUFFIX : "");
    }
}
