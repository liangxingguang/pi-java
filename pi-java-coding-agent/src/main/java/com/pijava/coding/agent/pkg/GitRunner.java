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

    // ── L5 W8（pi :62-79/:2948-3013）：capture 输出面 ────────────────
    /** End of a captured command's output kept for its error message. */
    static final int CAPTURED_OUTPUT_TAIL_CHARS = 8_000;
    /** Output of git, or of ssh run by git, that failed because it could not ask for credentials. */
    private static final java.util.regex.Pattern GIT_PROMPT_FAILURE = java.util.regex.Pattern.compile(
        "terminal prompts disabled|could not read (Username|Password)"
            + "|Permission denied \\(publickey|Host key verification failed|passphrase",
        java.util.regex.Pattern.CASE_INSENSITIVE);
    static final String GIT_CREDENTIALS_HINT =
        "git cannot ask for a password or passphrase while pi-java runs. Set up a git "
            + "credential helper or an ssh agent, or install the package once from the shell "
            + "with `pi-java install <source>`.";

    /** {@code commandOutput="capture"}（pi :192/921-928）：不碰终端、git/ssh 不可交互。 */
    private volatile boolean captureOutput;

    public void setCaptureOutput(boolean capture) {
        this.captureOutput = capture;
    }

    /** pi runCommand 失败（spawn 层错误/超时/非零）的统一异常（≙ pi reject）。 */
    public static final class GitCommandException extends RuntimeException {
        public GitCommandException(String message) {
            super(message);
        }

        public GitCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** pi {@code runCommand}（:2733-2747）：stdio 继承，无输出捕获；capture 模式见下。 */
    public void run(List<String> args, Path cwd) {
        if (captureOutput) {
            runCaptured(args, cwd);
            return;
        }
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

    /**
     * pi {@code runCommandCaptured}（:2977-3013）：stdio 全管、git/ssh 不可交互；
     * 只留输出尾部 {@value #CAPTURED_OUTPUT_TAIL_CHARS} 字符供错误消息。
     */
    private void runCaptured(List<String> args, Path cwd) {
        var builder = new ProcessBuilder(args);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        var env = builder.environment();
        // A user-defined GIT_SSH_COMMAND is kept: it may be needed to reach the host at all.
        env.putIfAbsent("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
        env.put("GIT_TERMINAL_PROMPT", "0");
        builder.redirectErrorStream(true);
        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GitCommandException(
                "Failed to spawn " + String.join(" ", args) + ": " + e.getMessage(), e);
        }
        // 抽干合并管（redirectErrorStream）防缓冲满死锁，再 waitFor。
        var drain = new StreamDrain(process.getInputStream());
        final int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitCommandException(String.join(" ", args) + " interrupted", e);
        }
        if (code == 0) {
            return;
        }
        var output = drain.join().trim();
        var tail = output.length() > CAPTURED_OUTPUT_TAIL_CHARS
            ? output.substring(output.length() - CAPTURED_OUTPUT_TAIL_CHARS) : output;
        var hint = args.get(0).equals("git") && GIT_PROMPT_FAILURE.matcher(tail).find()
            ? "\n" + GIT_CREDENTIALS_HINT : "";
        throw new GitCommandException(String.join(" ", args) + " failed with code " + code
            + (tail.isEmpty() ? "" : ":\n" + tail) + hint);
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
        // pi getNonInteractiveGitEnv：captured 命令不能应答提示。
        builder.environment().putIfAbsent("GIT_TERMINAL_PROMPT", "0");
        builder.environment().putIfAbsent("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
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
