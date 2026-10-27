package com.pijava.coding.agent.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.AbortSignal;

/**
 * 批 26（docs/37 T6，E5-17）：pi {@code execCommand}（core/exec.ts:1-107 逐字语义）——
 * 扩展执行外部命令。
 *
 * <ul>
 *   <li>无壳（pi {@code shell: false}）⇒ {@link ProcessBuilder} 直启；</li>
 *   <li>终止两段式：先 {@code destroy}（≙ SIGTERM），5 秒未死再 {@code destroyForcibly}
 *       （≙ SIGKILL；Windows 无信号区分，docs/37 §5 D-new5 窄差）；</li>
 *   <li>{@code timeout > 0} ⇒ 到点 kill；{@code signal} 已中止 ⇒ 立即 kill；</li>
 *   <li>spawn/等待出错 ⇒ {@code code: 1} 吞异常（pi catch 臂逐字）；</li>
 *   <li>结果 {@code {stdout, stderr, code, killed}}；正常退出 {@code code = code ?? 0}。</li>
 * </ul>
 */
public final class ExtensionExec {

    private ExtensionExec() {}

    /** pi {@code ExecOptions}（exec.ts:11-20）。 */
    public record ExecOptions(AbortSignal signal, Long timeoutMillis, String cwd) {}

    /** pi {@code ExecResult}（exec.ts:23-28）。 */
    public record ExecResult(String stdout, String stderr, int code, boolean killed) {}

    /**
     * pi {@code execCommand}（exec.ts:35-105）。
     *
     * @param cwdFallback 扩展加载 cwd（loader.ts:413 的 {@code options?.cwd ?? cwd} 对位）
     */
    public static ExecResult execCommand(String command, java.util.List<String> args,
                                         String cwdFallback, ExecOptions options) {
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        boolean killed = false;
        Process process = null;
        try {
            var commandLine = new java.util.ArrayList<String>();
            commandLine.add(command);
            commandLine.addAll(args);
            var builder = new ProcessBuilder(commandLine);
            var cwd = options != null && options.cwd() != null ? options.cwd() : cwdFallback;
            if (cwd != null) {
                builder.directory(Path.of(cwd).toFile());
            }
            // pi stdio: ["ignore", "pipe", "pipe"]——stdin 的 ignore 对位：
            // 起后立即关闭子进程 stdin（读到 EOF，无平台 devnull 依赖）。
            process = builder.start();
            process.getOutputStream().close();

            // 双泵：stdout/stderr 各一读取线程（pi 的 data 事件流对位）。
            var proc = process;
            var outPump = Thread.startVirtualThread(() -> pump(proc.getInputStream(), stdout));
            var errPump = Thread.startVirtualThread(() -> pump(proc.getErrorStream(), stderr));

            boolean killedNow = false;
            var signal = options == null ? null : options.signal();
            Runnable unsubscribe = null;
            if (signal != null) {
                // AbortSignal.onAbort：已中止 ⇒ 立即跑（pi 的 signal.aborted 预检臂
                // 由此覆盖）；返回退订柄。
                final boolean[] flag = {false};
                unsubscribe = signal.onAbort(() -> {
                    flag[0] = true;
                    killProcess(proc);
                });
                killedNow = flag[0];
            }

            Long timeout = options == null ? null : options.timeoutMillis();
            int code;
            try {
                if (timeout != null && timeout > 0) {
                    if (!proc.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                        killProcess(proc);
                        killedNow = true;
                        proc.waitFor();
                    }
                } else {
                    proc.waitFor();
                }
                code = proc.exitValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killProcess(proc);
                proc.waitFor();
                killedNow = true;
                code = 1;
            }
            if (unsubscribe != null) {
                unsubscribe.run();
            }
            outPump.join();
            errPump.join();
            // pi 的 killed 标记：signal/timeout 路径（含等待被打断的兜底）。
            boolean killedFlag = killedNow;
            return new ExecResult(stdout.toString(), stderr.toString(), code, killedFlag);
        } catch (IOException | InterruptedException e) {
            // pi catch 臂（exec.ts:97-104）：吞异常 ⇒ code 1
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecResult(stdout.toString(), stderr.toString(), 1, killed);
        }
    }

    /** pi {@code killProcess}：先 soft，5 秒未死再 forcibly。 */
    private static void killProcess(Process process) {
        process.destroy();
        Thread.startVirtualThread(() -> {
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        });
    }

    private static void pump(java.io.InputStream in, StringBuilder out) {
        try {
            out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 进程被杀后管道关闭：泵静默收（pi 的流 end 同形）。
        }
    }
}
