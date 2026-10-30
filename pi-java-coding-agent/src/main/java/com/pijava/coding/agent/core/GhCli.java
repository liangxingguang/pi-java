package com.pijava.coding.agent.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Process boundary for GitHub CLI authentication and gist creation. */
public final class GhCli {

    /** Completed process output. */
    public record Result(int exitCode, String stdout, String stderr) {}

    /** One gh invocation; arguments exclude the executable name. */
    @FunctionalInterface
    public interface Runner {
        Result run(List<String> arguments) throws IOException, InterruptedException;
    }

    private GhCli() {}

    /** Production runner using ProcessBuilder without a shell. */
    public static Runner processRunner() {
        return arguments -> {
            var command = new ArrayList<String>();
            command.add("gh");
            command.addAll(arguments);
            var process = new ProcessBuilder(command).start();
            var stdout = readAsync(process.getInputStream());
            var stderr = readAsync(process.getErrorStream());
            try {
                int exit = process.waitFor();
                return new Result(exit, text(stdout), text(stderr));
            } catch (InterruptedException e) {
                process.destroyForcibly();
                throw e;
            }
        };
    }

    private static CompletableFuture<byte[]> readAsync(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return input.readAllBytes();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Thread::startVirtualThread);
    }

    private static String text(CompletableFuture<byte[]> output) throws IOException {
        try {
            return new String(output.join(), StandardCharsets.UTF_8);
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }
}
