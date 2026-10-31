package com.pijava.evals.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 批 32（docs/44 §7.2）：docker 进程缝 —— 生产走 {@link ProcessBuilder}，测试注入
 * 假实现记录 argv 与返回码/输出。
 */
@FunctionalInterface
public interface DockerProcess {

    /**
     * Run one docker command.
     *
     * @param command argv
     * @return exit code and captured output
     */
    Result run(List<String> command);

    /**
     * Captured process result.
     *
     * @param exitCode exit code
     * @param stdout   standard output (stderr merged into it by the real process)
     * @param stderr   standard error (empty for the real process)
     */
    record Result(int exitCode, String stdout, String stderr) {

        /** Normalizes nullable output. */
        public Result {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }

    /**
     * Real process-based implementation; stderr is merged into stdout.
     *
     * @return docker process
     */
    static DockerProcess real() {
        return command -> {
            Objects.requireNonNull(command, "command");
            try {
                var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
                var output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                var exitCode = process.waitFor();
                return new Result(exitCode, output, "");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to run docker command: " + command, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while running docker command", e);
            }
        };
    }
}
