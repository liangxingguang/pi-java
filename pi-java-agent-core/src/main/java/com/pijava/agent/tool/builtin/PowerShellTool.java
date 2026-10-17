package com.pijava.agent.tool.builtin;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.PowerShellShellExecutor;

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
 *       {@code DEFAULT_TOOL_NAMES} 恒四件 —— 默认不激活（裁决③，docs/24 §13.3）。</li>
 * </ul>
 */
public final class PowerShellTool {

    /** pi powershell.ts:16 逐字。 */
    static final String UTF8_OUTPUT_PREFIX =
        "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}\n";

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
        return ShellToolSupport.create(POWERSHELL_CONFIG, null, exposeSessionEnvironment,
            PowerShellTool::wrapCommand,
            new PowerShellShellExecutor());
    }

    /** pi :35 的 exec 包装：前置 UTF8 输出前缀。 */
    static String wrapCommand(String command) {
        return UTF8_OUTPUT_PREFIX + command;
    }
}
