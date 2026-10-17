package com.pijava.agent.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultShellExecutorTest {

    @TempDir
    Path tmp;

    @Test
    void customShellPathMissingThrowsActionableError() {
        var executor = new DefaultShellExecutor(
            tmp.resolve("no-such-bash.exe").toString());

        assertThatThrownBy(() -> executor.execute(
                "echo hi", options(tmp)))
            .hasMessageContaining("Custom shell path not found");
    }

    @Test
    void customShellPathRunsCommandViaStdin() throws Exception {
        Path fakeBash = createFakeBash();
        var executor = new DefaultShellExecutor(fakeBash.toString());

        var result = executor.execute("echo hello-bash", options(tmp));

        assertThat(result.exitCode()).isZero();
        // Windows shells receive the command over stdin (bash --login -s) so
        // escapes like `\n` survive MSYS argument conversion untouched.
        assertThat(result.output()).contains("echo hello-bash");
    }

    @Test
    void timeoutSnapshotContainsAllBytesWrittenBeforeKill() throws Exception {
        // 契约钉：超时刻收编的部分输出必须完整（kill 前写出的全部字节）。
        // destroy 后读取线程还在 drain 管道残量，不 join 就快照会概率性丢尾
        // （批 3 评审发现 2）——竞窗口 <5ms 且叠加 login-shell 启动抖动，
        // 公共 API 上做不出确定性 RED，此测试钉终态契约，join 结构性保证。
        var probe = new DefaultShellExecutor();
        try {
            probe.execute("true", options(tmp));
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "no bash on this host: " + e.getMessage());
        }

        var executor = new DefaultShellExecutor();
        var opts = new ShellOptions(tmp.toString(), Map.of(), true,
            OptionalLong.of(6_000), null);

        var result = executor.execute(
            "head -c 3000000 /dev/zero | tr '\\0' 'x'; sleep 30", opts);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.output()).hasSize(3_000_000);
    }

    private Path createFakeBash() throws Exception {
        if (isWindows()) {
            // A .cmd is spawnable directly on Windows; it reads the command
            // from stdin (stdin transport) and echoes it back.
            Path script = tmp.resolve("bash.cmd");
            Files.writeString(script,
                "@echo off\r\nfindstr /r \".*\"\r\n");
            return script;
        }
        Path script = tmp.resolve("bash");
        Files.writeString(script, "#!/bin/sh\necho \"$@\"\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private static ShellOptions options(Path cwd) {
        return new ShellOptions(
            cwd.toString(), Map.of(), true, OptionalLong.empty(), null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
