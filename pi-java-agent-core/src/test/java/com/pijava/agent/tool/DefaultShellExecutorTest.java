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
