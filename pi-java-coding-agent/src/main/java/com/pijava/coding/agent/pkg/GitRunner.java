package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * pi spawn 面（package-manager.ts:2664-2757）的 ProcessBuilder 对位——
 * {@code runCommand}/{@code runCommandCapture} 两臂：
 *
 * <ul>
 *   <li>capture：pipe stdout/stderr，成功 ⇒ stdout.trim()；超时 kill ⇒
 *       {@code <cmd> <args> timed out after <ms>ms}（:2719 逐字）；非零 ⇒
 *       {@code <cmd> <args> failed with code <n>: <stderr|stdout>}（:2727 逐字）。</li>
 *   <li>run：stdio 继承；非零 ⇒ {@code <cmd> <args> failed with code <n>}。</li>
 *   <li>远程 capture（ls-remote）统一 env {@code GIT_TERMINAL_PROMPT=0}
 *       （:1685-1687 {@code runGitRemoteCommand} 对位）。</li>
 * </ul>
 *
 * <p>pi {@code NETWORK_TIMEOUT_MS}＝10s（:50），只挂 capture 臂；clone/fetch/
 * reset 的 run 臂同 pi 不设超时。信号终止的 {@code signal <sig>} 形无 POSIX
 * 对位（Java 只暴露退出码）⇒ 并入 code 文案。</p>
 *
 * <p>非 final：测试子类化记录 env/调用面（{@code GIT_TERMINAL_PROMPT} 断言）。</p>
 */
public class GitRunner {

    /** pi {@code NETWORK_TIMEOUT_MS}（:50）。 */
    public static final long NETWORK_TIMEOUT_MS = 10_000;

    /** pi runCommand 失败（spawn 层错误/超时/非零）的统一异常（≙ pi reject）。 */
    public static final class GitCommandException extends RuntimeException {
        public GitCommandException(String message) {
            super(message);
        }

        public GitCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** pi {@code runCommand}（:2733-2747）：stdio 继承，无输出捕获。 */
    public void run(List<String> args, Path cwd) {
        var builder = new ProcessBuilder(args);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.inheritIO();
        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitCommandException(
                "Failed to spawn " + String.join(" ", args) + ": " + e.getMessage(), e);
        }
        final int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitCommandException(String.join(" ", args) + " interrupted", e);
        }
        if (code != 0) {
            throw new GitCommandException(String.join(" ", args) + " failed with code " + code);
        }
    }

    /** pi {@code runCommandCapture}（:2690-2731）。 */
    public String capture(List<String> args, Path cwd, long timeoutMs) {
        return capture(args, cwd, timeoutMs, Map.of());
    }

    /** capture＋附加 env（pi {@code runGitRemoteCommand} 的
     *  {@code GIT_TERMINAL_PROMPT=0} 对位）。 */
    public String capture(List<String> args, Path cwd, long timeoutMs, Map<String, String> extraEnv) {
        var builder = new ProcessBuilder(args);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.environment().putAll(extraEnv);
        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitCommandException(
                "Failed to spawn " + String.join(" ", args) + ": " + e.getMessage(), e);
        }
        // 并发抽干两管，防 stderr 缓冲满死锁。
        var stdout = new StreamDrain(process.getInputStream());
        var stderr = new StreamDrain(process.getErrorStream());
        boolean timedOut;
        try {
            timedOut = !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (timedOut) {
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitCommandException(String.join(" ", args) + " interrupted", e);
        }
        var out = stdout.join();
        var err = stderr.join();
        if (timedOut) {
            throw new GitCommandException(
                String.join(" ", args) + " timed out after " + timeoutMs + "ms");
        }
        if (process.exitValue() == 0) {
            return out.trim();
        }
        throw new GitCommandException(
            String.join(" ", args) + " failed with code " + process.exitValue()
                + ": " + (!err.isEmpty() ? err : out));
    }

    /** pi {@code runGitRemoteCommand}（:1685-1691）：{@code GIT_TERMINAL_PROMPT=0}
     *  ＋网络超时。 */
    public String remoteCapture(List<String> args, Path cwd) {
        return capture(args, cwd, NETWORK_TIMEOUT_MS, Map.of("GIT_TERMINAL_PROMPT", "0"));
    }

    /** 后台线程抽干管道（虚拟线程，随 JVM 退出）。 */
    private static final class StreamDrain {
        private final Thread thread;
        private volatile String result = "";
        private volatile IOException failure;

        StreamDrain(java.io.InputStream stream) {
            thread = Thread.startVirtualThread(() -> {
                try {
                    result = new String(stream.readAllBytes(), Charset.defaultCharset());
                } catch (IOException e) {
                    failure = e;
                }
            });
        }

        String join() {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return failure != null ? "" : result;
        }
    }
}
