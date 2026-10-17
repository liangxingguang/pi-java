package com.pijava.agent.tool;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程执行引擎（B243 重构）：{@link DefaultShellExecutor}（bash 解析）与
 * {@link PowerShellShellExecutor}（pwsh → powershell 解析，pi
 * {@code utils/shell.ts:122-136}）共用的泵 —— 拉起进程、流式读输出、超时/中止、
 * 快照 {@link ShellResult}。解析 shell 是调用方的事，这里只吃定形的 argv。
 */
final class ShellProcessEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ShellProcessEngine.class);
    private static final int MAX_LOG_COMMAND_LENGTH = 500;

    private ShellProcessEngine() {}

    /**
     * 执行 {@code command}：{@code stdinTransport} 为 true 时经 stdin 喂入
     * （Git Bash/WSL 路），否则追加为 argv 末参（{@code -c}/{@code -Command} 路）。
     */
    static ShellResult run(String command, ShellOptions options,
                           List<String> argv, boolean stdinTransport) throws Exception {
        long startNanos = System.nanoTime();
        LOG.debug("shell exec: cwd={} command={}", options.cwd(), truncate(command));
        var pb = new ProcessBuilder();
        if (stdinTransport) {
            pb.command(List.copyOf(argv));
        } else {
            pb.command(concat(argv, command));
        }
        pb.directory(Path.of(options.cwd()).toFile());
        if (!options.inheritEnv()) {
            pb.environment().clear();
        }
        // pi bash.ts:194-198 的 delete×5：先删后设，防继承环境污染/伪造（B242）。
        options.envRemovals().forEach(pb.environment()::remove);
        pb.environment().putAll(options.env());
        pb.redirectErrorStream(true);

        var process = pb.start();
        if (stdinTransport) {
            process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
        }
        var output = new ByteArrayOutputStream();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                // 包⑧（原 docs/35）：按**字符边界**增量解码后再报给汇 —— 8 KiB 的切点会
                // 落在任意字节上，直接 new String(buf,0,n) 会把汉字/emoji 劈成替换符。
                var sink = options.outputSink();
                var chunkStream = sink == null ? null : new Utf8ChunkStream();
                try (var is = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (options.signal() != null && options.signal().isAborted()) {
                            process.destroyForcibly();
                            break;
                        }
                        output.write(buf, 0, n);
                        if (sink != null) {
                            emitTo(sink, chunkStream.accept(buf, 0, n));
                        }
                    }
                }
                if (sink != null) {
                    // 收尾：残片（真的只有半个字符）也要报出去，否则汇的拼接结果
                    // 与终局 output() 不等。
                    emitTo(sink, chunkStream.finish());
                }
                return process.waitFor();
            });

            int exitCode;
            boolean timedOut = false;
            try {
                if (options.timeoutMillis().isPresent()) {
                    exitCode = future.get(options.timeoutMillis().getAsLong(), TimeUnit.MILLISECONDS);
                } else {
                    exitCode = future.get();
                }
            } catch (TimeoutException e) {
                timedOut = true;
                process.destroyForcibly();
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    exitCode = -1;
                }
                // 快照前 join 读取线程：进程死亡后管道残量要 drain 完，否则超时面的
                // 「部分输出」概率性丢尾（批 3 评审发现 2；非超时路径 future.get 已含 join）。
                try {
                    future.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // 读取线程失败：已 drain 的部分照用（进程已毁，输出不会再长）。
                }
            }

            String outputStr = output.toString(StandardCharsets.UTF_8);
            long outputLines = outputStr.isEmpty() ? 0 : outputStr.lines().count();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.debug("shell exit: code={} timedOut={} lines={} elapsedMs={}",
                exitCode, timedOut, outputLines, elapsedMs);

            return new ShellResult(
                outputStr,
                exitCode,
                timedOut,
                false,  // truncation handled by the tool layer
                outputLines,
                outputStr.getBytes(StandardCharsets.UTF_8).length
            );
        }
    }

    /**
     * 把一块非空输出报给汇。空块不报 —— 免得给下游制造「有更新但内容没变」的帧。
     *
     * <p>汇抛异常时**不吞**：它属于宿主代码，静默吞掉会让「明明订阅了却什么都没收到」
     * 变成不可诊断的哑失败。</p>
     */
    private static void emitTo(ShellOutputSink sink, String delta) {
        if (!delta.isEmpty()) {
            sink.onOutput(delta);
        }
    }

    private static List<String> concat(List<String> args, String command) {
        var all = new ArrayList<String>(args);
        all.add(command);
        return all;
    }

    private static String truncate(String text) {
        String singleLine = text.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= MAX_LOG_COMMAND_LENGTH
            ? singleLine : singleLine.substring(0, MAX_LOG_COMMAND_LENGTH) + "...";
    }
}
