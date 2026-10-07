package com.pijava.mcp.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Process launch and process-tree termination for stdio transport
 * (stdio.ts:96-141).
 */
final class StdioProcess {

    /** A started process with its stdin. */
    record Launched(Process process, OutputStream stdin) {
    }

    private StdioProcess() {
    }

    /** Launch a child per the options. */
    static Launched start(StdioTransportOptions options) throws IOException {
        var pb = new ProcessBuilder();
        var command = new ArrayList<String>();
        command.add(options.command());
        command.addAll(options.args());
        pb.command(command);
        if (options.cwd() != null) {
            pb.directory(new java.io.File(options.cwd()));
        }

        var environment = pb.environment();
        if (!options.inheritEnv()) {
            environment.clear();
        }
        if (options.env() != null) {
            for (Map.Entry<String, String> entry : options.env().entrySet()) {
                environment.put(entry.getKey(), entry.getValue());
            }
        }

        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(stderrRedirect(options.stderr()));
        var process = pb.start();
        return new Launched(process, process.getOutputStream());
    }

    private static ProcessBuilder.Redirect stderrRedirect(StdioTransportOptions.Stderr routing) {
        return switch (routing) {
            case PIPE -> ProcessBuilder.Redirect.PIPE;
            case INHERIT -> ProcessBuilder.Redirect.INHERIT;
            case IGNORE -> ProcessBuilder.Redirect.DISCARD;
        };
    }

    /**
     * Terminate the child and every descendant, then wait the grace period
     * (the SIGTERM equivalent).
     */
    static void terminateTree(Process child, long timeoutMs) throws InterruptedException {
        var handle = child.toHandle();
        handle.descendants().forEach(java.lang.ProcessHandle::destroy);
        handle.destroy();
        child.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Kill the child and every descendant; wait until dead (the SIGKILL equivalent). */
    static void killTree(Process child) throws InterruptedException {
        var handle = child.toHandle();
        handle.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
        child.waitFor();
    }
}
