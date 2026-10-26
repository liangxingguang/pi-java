package com.pijava.coding.agent.extension.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

import com.pijava.coding.agent.core.SettingsAccessors;
import com.pijava.mcp.runtime.CommandRunner;
import com.pijava.mcp.runtime.JdkShellRunner;

/**
 * 批 24（docs/35 T4，B188 销号）：{@code !cmd} 的两段式壳（pi
 * {@code resolve-config-value.ts} 逐字语义——先 {@code settings.shellPath}
 * 配置的壳，spawn 失败（ENOENT 族）回落默认壳；命令跑了但非零退出
 * <b>不</b>回落）。语义逐字复制 {@link JdkShellRunner}（10s 超时、stderr 丢弃、
 * 非零 ⇒ null），仅壳可配。
 */
final class ShellPathCommandRunner implements CommandRunner {

    private static final long TIMEOUT_MS = 10_000;
    private static final long READER_GRACE_MS = 1_000;
    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final SettingsAccessors settings;
    private final JdkShellRunner fallback = new JdkShellRunner();

    private ShellPathCommandRunner(SettingsAccessors settings) {
        this.settings = settings;
    }

    /** 两段式 runner：shellPath 未配置 ⇒ 恒默认壳（零行为变化）。 */
    static CommandRunner twoTier(SettingsAccessors settings) {
        return new ShellPathCommandRunner(settings);
    }

    @Override
    public @Nullable String run(String command) {
        var shellPath = settings.getShellPath();
        if (shellPath == null || shellPath.isBlank()) {
            return fallback.run(command);
        }
        try {
            return runWith(shellPath, command);
        } catch (SpawnFailed spawnFailed) {
            // pi：shell 不存在（ENOENT）⇒ 回落默认壳
            return fallback.run(command);
        }
    }

    /** @throws SpawnFailed 壳进程起不来（pi 的 ENOENT 臂） */
    private @Nullable String runWith(String shell, String command) {
        // Node spawn(command, {shell}) 的参数约定：Windows /d /s /c，其余 -c
        var builder = WINDOWS
            ? new ProcessBuilder(List.of(shell, "/d", "/s", "/c", command))
            : new ProcessBuilder(List.of(shell, "-c", command));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotSpawn) {
            throw new SpawnFailed(cannotSpawn);
        }
        try (var stdout = process.getInputStream()) {
            var reader = CompletableFuture.supplyAsync(() -> readAll(stdout),
                Thread::startVirtualThread);
            byte[] bytes;
            try {
                bytes = reader.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                process.destroyForcibly();
                return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return null;
            } catch (ExecutionException failure) {
                process.destroyForcibly();
                return null;
            }
            if (!process.waitFor(READER_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException expected) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static byte[] readAll(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (IOException expected) {
            return new byte[0];
        }
    }

    /** spawn 失败标记（pi ENOENT 的可判定位）。 */
    private static final class SpawnFailed extends RuntimeException {
        SpawnFailed(IOException cause) {
            super(cause);
        }
    }
}
