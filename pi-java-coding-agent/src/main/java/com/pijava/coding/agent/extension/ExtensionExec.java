package com.pijava.coding.agent.extension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p>L5 W3（B277a）：补 {@code maxBuffer}（stdout/stderr <b>合并</b>字节上限；超限 ⇒
 * {@code truncated: true}、关两条管道、kill；截断结果 {@code code} 若为 0 强制为 1）
 * 与跨块 UTF-8 解码（截断时丢弃不完整尾序列，不追加替换字符）。</p>
 */
public final class ExtensionExec {

    /** pi {@code DEFAULT_EXEC_MAX_BUFFER}：256 MiB（运行时字符串上限更高，取小者）。 */
    public static final long DEFAULT_EXEC_MAX_BUFFER = 256L * 1024 * 1024;

    /** 与 {@link #DEFAULT_EXEC_MAX_BUFFER} 同值；pi 的校验与默认在 Java 侧合并为此上限。 */
    static final long MAX_BUFFER_LIMIT = DEFAULT_EXEC_MAX_BUFFER;

    private static final int PUMP_CHUNK_BYTES = 64 * 1024;

    private ExtensionExec() {}

    /**
     * pi {@code ExecOptions}（exec.ts:11-20）。
     *
     * <p>{@code maxBuffer} 为 {@code null} ⇒ {@link #DEFAULT_EXEC_MAX_BUFFER}；非负且
     * 不超过 256 MiB，否则构造即抛 {@link IllegalArgumentException}（pi 的
     * {@code RangeError} 臂）。</p>
     */
    public record ExecOptions(AbortSignal signal, Long timeoutMillis, String cwd,
                              Long maxBuffer) {

        /** 三参旧形状（无 maxBuffer）。 */
        public ExecOptions(AbortSignal signal, Long timeoutMillis, String cwd) {
            this(signal, timeoutMillis, cwd, null);
        }

        /** pi exec.ts:41 的校验臂。 */
        public ExecOptions {
            if (maxBuffer != null && (maxBuffer < 0 || maxBuffer > MAX_BUFFER_LIMIT)) {
                throw new IllegalArgumentException(
                    "maxBuffer must be an integer between 0 and " + MAX_BUFFER_LIMIT + " bytes");
            }
        }
    }

    /** pi {@code ExecResult}（exec.ts:23-28）。 */
    public record ExecResult(String stdout, String stderr, int code, boolean killed,
                             boolean truncated) {

        /** 四参旧形状（无 truncated）。 */
        public ExecResult(String stdout, String stderr, int code, boolean killed) {
            this(stdout, stderr, code, killed, false);
        }
    }

    /**
     * pi {@code execCommand}（exec.ts:35-148）。
     *
     * @param cwdFallback 扩展加载 cwd（loader.ts:413 的 {@code options?.cwd ?? cwd} 对位）
     */
    public static ExecResult execCommand(String command, java.util.List<String> args,
                                         String cwdFallback, ExecOptions options) {
        long maxBuffer = options == null || options.maxBuffer() == null
            ? DEFAULT_EXEC_MAX_BUFFER : options.maxBuffer();
        var stdoutBytes = new ByteArrayOutputStream();
        var stderrBytes = new ByteArrayOutputStream();
        var outputBytes = new AtomicLong();
        var truncated = new AtomicBoolean(false);
        var killed = new AtomicBoolean(false);
        var capLock = new Object();
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

            // 双泵：stdout/stderr 各一读取线程（pi 的 data 事件流对位）；合并计字节。
            var proc = process;
            var outPump = Thread.startVirtualThread(() -> pump(proc.getInputStream(),
                stdoutBytes, outputBytes, maxBuffer, capLock, truncated,
                () -> overflow(proc, truncated, killed)));
            var errPump = Thread.startVirtualThread(() -> pump(proc.getErrorStream(),
                stderrBytes, outputBytes, maxBuffer, capLock, truncated,
                () -> overflow(proc, truncated, killed)));

            var signal = options == null ? null : options.signal();
            Runnable unsubscribe = null;
            if (signal != null) {
                // AbortSignal.onAbort：已中止 ⇒ 立即跑（pi 的 signal.aborted 预检臂
                // 由此覆盖）；返回退订柄。
                unsubscribe = signal.onAbort(() -> killProcess(proc, killed));
            }

            Long timeout = options == null ? null : options.timeoutMillis();
            int code;
            try {
                if (timeout != null && timeout > 0) {
                    if (!proc.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                        killProcess(proc, killed);
                        proc.waitFor();
                    }
                } else {
                    proc.waitFor();
                }
                code = proc.exitValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killProcess(proc, killed);
                proc.waitFor();
                code = 1;
            }
            if (unsubscribe != null) {
                unsubscribe.run();
            }
            outPump.join();
            errPump.join();
            // 截断结果绝不能读作成功（pi exec.ts:145-147）。
            if (truncated.get() && code == 0) {
                code = 1;
            }
            return new ExecResult(
                decode(stdoutBytes.toByteArray(), truncated.get()),
                decode(stderrBytes.toByteArray(), truncated.get()),
                code, killed.get(), truncated.get());
        } catch (IOException | InterruptedException e) {
            // pi catch 臂（exec.ts:130-136）：吞异常 ⇒ code 1
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecResult(
                decode(stdoutBytes.toByteArray(), truncated.get()),
                decode(stderrBytes.toByteArray(), truncated.get()),
                1, killed.get(), truncated.get());
        }
    }

    /** 超限：关两条管道、kill（pi exec.ts:95-104 的 {@code takeWithinCap} 溢出臂）。 */
    private static void overflow(Process process, AtomicBoolean truncated,
                                 AtomicBoolean killed) {
        truncated.set(true);
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        killProcess(process, killed);
    }

    /** pi {@code killProcess}：先 soft，5 秒未死再 forcibly；置 killed（pi 的同名旗）。 */
    private static void killProcess(Process process, AtomicBoolean killed) {
        if (killed.getAndSet(true)) {
            return;
        }
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

    /**
     * 分块读取并只接受合并上限内的字节（pi {@code takeWithinCap}）。超限后不再追加，
     * 记录 {@code truncated} 并由 {@code onOverflow} 关流＋kill。
     */
    private static void pump(InputStream in, ByteArrayOutputStream out,
                             AtomicLong outputBytes, long maxBuffer, Object capLock,
                             AtomicBoolean truncated, Runnable onOverflow) {
        byte[] buffer = new byte[PUMP_CHUNK_BYTES];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                int accepted;
                boolean over;
                synchronized (capLock) {
                    long remaining = maxBuffer - outputBytes.get();
                    if (read <= remaining) {
                        outputBytes.addAndGet(read);
                        accepted = read;
                        over = false;
                    } else {
                        accepted = (int) Math.max(0, remaining);
                        outputBytes.addAndGet(accepted);
                        over = true;
                    }
                }
                if (accepted > 0) {
                    out.write(buffer, 0, accepted);
                }
                if (over) {
                    onOverflow.run();
                    return;
                }
                if (truncated.get()) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // 进程被杀后管道关闭：泵静默收（pi 的流 end 同形）。
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 同 pump 的静默收策略。
        }
    }

    /**
     * 截断时先剥离末尾不完整的 UTF-8 序列再解码（pi 的 {@code StringDecoder.end()}
     * 只在未截断时 flush）。非截断路径等价于整段解码。
     */
    static String decode(byte[] bytes, boolean truncated) {
        return new String(truncated ? completeUtf8Prefix(bytes) : bytes,
            StandardCharsets.UTF_8);
    }

    /** 返回去掉末尾不完整 UTF-8 序列后的前缀。 */
    static byte[] completeUtf8Prefix(byte[] bytes) {
        int end = bytes.length;
        for (int back = 1; back <= 3 && back <= end; back++) {
            int b = bytes[end - back] & 0xFF;
            if ((b & 0x80) == 0) {
                return bytes;
            }
            if ((b & 0xC0) == 0x80) {
                continue;
            }
            int expected = (b & 0xE0) == 0xC0 ? 2
                : (b & 0xF0) == 0xE0 ? 3
                : (b & 0xF8) == 0xF0 ? 4 : -1;
            if (expected < 0) {
                return bytes;
            }
            return back < expected ? Arrays.copyOf(bytes, end - back) : bytes;
        }
        return bytes;
    }
}
