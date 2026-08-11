package com.pijava.agent.tool;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default shell executor using ProcessBuilder + Virtual Threads.
 * Captures stdout+stderr combined, enforces timeout via Future.get().
 * Truncation is handled by the BashTool layer, not here.
 */
public class DefaultShellExecutor implements ShellExecutor {

    @Override
    public ShellResult execute(String command, ShellOptions options) throws Exception {
        var pb = new ProcessBuilder();
        // Use shell to execute command for cross-platform compatibility
        if (isWindows()) {
            pb.command("cmd", "/c", command);
        } else {
            pb.command("sh", "-c", command);
        }
        pb.directory(java.nio.file.Path.of(options.cwd()).toFile());
        if (!options.inheritEnv()) {
            pb.environment().clear();
        }
        pb.environment().putAll(options.env());
        pb.redirectErrorStream(true);

        var process = pb.start();
        var output = new ByteArrayOutputStream();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                try (var is = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (options.signal() != null && options.signal().isAborted()) {
                            process.destroyForcibly();
                            break;
                        }
                        output.write(buf, 0, n);
                    }
                }
                return process.waitFor();
            });

            int exitCode;
            boolean timedOut = false;
            try {
                if (options.timeoutSeconds().isPresent()) {
                    exitCode = future.get(options.timeoutSeconds().getAsLong(), TimeUnit.SECONDS);
                } else {
                    exitCode = future.get();
                }
            } catch (TimeoutException e) {
                timedOut = true;
                process.destroyForcibly();
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException ie) {
                    exitCode = -1;
                }
            }

            String outputStr = output.toString(StandardCharsets.UTF_8);
            long outputLines = outputStr.isEmpty() ? 0 : outputStr.lines().count();

            return new ShellResult(
                outputStr,
                exitCode,
                timedOut,
                false,  // truncation handled by BashTool
                outputLines,
                outputStr.getBytes(StandardCharsets.UTF_8).length
            );
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
