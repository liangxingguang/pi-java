package com.pijava.agent.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Assumptions;
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
    void customShellPathRunsCommandViaDashC() throws Exception {
        Path fakeBash = createFakeBash();
        var executor = new DefaultShellExecutor(fakeBash.toString());

        var result = executor.execute("echo hello-bash", options(tmp));

        assertThat(result.exitCode()).isZero();
        // The fake bash echoes its arguments; the command must arrive as an
        // argv argument after the shell flags (bash --login -c <command>).
        assertThat(result.output()).contains("echo hello-bash");
    }

    @Test
    void discoversGitBashFromGitOnPathWhenPresent() throws Exception {
        Assumptions.assumeTrue(isWindows(), "Git-derived discovery is Windows-only");
        Path git = firstOnPath("git.exe");
        Assumptions.assumeTrue(git != null, "git.exe not on PATH");
        var root = git.getParent().getParent();
        Assumptions.assumeTrue(
            Files.exists(root.resolve("bin").resolve("bash.exe")),
            "no Git Bash installed next to git.exe");

        var executor = new DefaultShellExecutor();
        var result = executor.execute("echo discovered-bash", options(tmp));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("discovered-bash");
    }

    private Path createFakeBash() throws Exception {
        if (isWindows()) {
            // A .cmd is spawnable directly on Windows and echoes its arguments.
            Path script = tmp.resolve("bash.cmd");
            Files.writeString(script, "@echo off\r\necho %*\r\n");
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

    private static Path firstOnPath(String executable) throws Exception {
        var pb = new ProcessBuilder("where", executable);
        pb.redirectErrorStream(true);
        var process = pb.start();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        var first = new String(process.getInputStream().readAllBytes())
            .lines().findFirst().orElse("").trim();
        return first.isEmpty() || !Files.exists(Path.of(first))
            ? null : Path.of(first);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
