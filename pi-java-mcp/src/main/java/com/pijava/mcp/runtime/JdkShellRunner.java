package com.pijava.mcp.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

/**
 * {@link CommandRunner} backed by the JDK, mirroring Node's {@code execSync} defaults
 * ({@code resolve-config-value.ts:185-196}): {@code /bin/sh -c} on POSIX and
 * {@code cmd.exe /d /s /c} on Windows, stderr discarded, 10 second timeout.
 *
 * <p>pi's first tier — the shell configured in settings.json — is not reachable from this
 * module (the MCP module cannot see those settings), so this is always the default shell. A
 * host that has settings injects its own {@link CommandRunner} instead.</p>
 */
public final class JdkShellRunner implements CommandRunner {

    /** pi passes {@code timeout: 10000} to the shell ({@code resolve-config-value.ts:160,190}). */
    private static final long TIMEOUT_MS = 10_000;

    /** Extra budget for the reader thread after the process timeout fired. */
    private static final long READER_GRACE_MS = 1_000;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Override
    public @Nullable String run(String command) {
        var builder = WINDOWS
                ? new ProcessBuilder(List.of("cmd.exe", "/d", "/s", "/c", command))
                : new ProcessBuilder(List.of("/bin/sh", "-c", command));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = builder.start();
        } catch (IOException expected) {
            return null;
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
            return new String(bytes, StandardCharsets.UTF_8);
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
}
