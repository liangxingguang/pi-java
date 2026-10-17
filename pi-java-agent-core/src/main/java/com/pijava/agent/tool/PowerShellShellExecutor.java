package com.pijava.agent.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PowerShell 执行器（B243）—— pi {@code getPowerShellConfig}
 * （{@code utils/shell.ts:122-136}）对位：Windows 限定，{@code pwsh.exe} 优先、
 * {@code powershell.exe} 回落，{@link #POWERSHELL_ARGS} 五参逐字。
 * 进程泵复用 {@link ShellProcessEngine}（与 bash 同一超时/中止/流式语义）。
 */
public final class PowerShellShellExecutor implements ShellExecutor {

    /** pi {@code POWERSHELL_ARGS}（shell.ts:122）逐字。 */
    static final List<String> POWERSHELL_ARGS =
        List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command");

    @Override
    public ShellResult execute(String command, ShellOptions options) throws Exception {
        var argv = new ArrayList<String>();
        argv.add(resolvePowerShell());
        argv.addAll(POWERSHELL_ARGS);
        return ShellProcessEngine.run(command, options, argv, false);
    }

    /**
     * pi {@code getPowerShellConfig}（:124-135）：平台门在**执行时**（不在注册时），
     * 错文逐字。
     */
    static String resolvePowerShell() throws IOException {
        if (!DefaultShellExecutor.isWindows()) {
            throw new IOException("The powershell tool is only available on Windows.");
        }
        String shell = DefaultShellExecutor.firstOnPath("pwsh.exe");
        if (shell == null) {
            shell = DefaultShellExecutor.firstOnPath("powershell.exe");
        }
        if (shell == null) {
            throw new IOException("No PowerShell executable found. "
                + "Install PowerShell or add powershell.exe/pwsh.exe to PATH.");
        }
        return shell;
    }
}
