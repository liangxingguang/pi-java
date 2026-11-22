package com.pijava.mcp.transport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

/**
 * An in-memory pipe: the test pushes the program's output and decides when it ends
 * (pi {@code test/fixture/pipe.ts}).
 */
final class PipeFixture implements PipeTransport.McpPipe {

    private final CompletableFuture<Object> exited = new CompletableFuture<>();
    private final List<String> written = new CopyOnWriteArrayList<>();
    private volatile PipeTransport.@Nullable PipeHandlers handlers;
    private volatile boolean stdinClosed;
    private volatile boolean closed;
    private volatile boolean exitWhenStdinCloses;

    /** The opener that hands this pipe to the transport. */
    PipeTransport.PipeOpener opener() {
        return opened -> {
            this.handlers = opened;
            return this;
        };
    }

    /** Exit as soon as the transport closes stdin, like a cooperative server. */
    PipeFixture exitWhenStdinCloses() {
        this.exitWhenStdinCloses = true;
        return this;
    }

    /** Push stdout bytes to the transport. */
    void stdout(String text) {
        handlers().onStdout().accept(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Push stderr bytes to the transport. */
    void stderr(String text) {
        handlers().onStderr().accept(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The program ended normally. */
    void exit() {
        exited.complete(null);
    }

    /** The program ended badly. */
    void fail(Throwable error) {
        exited.completeExceptionally(error);
    }

    /** Everything written to the program, in order. */
    List<String> written() {
        return written;
    }

    boolean stdinClosed() {
        return stdinClosed;
    }

    boolean closed() {
        return closed;
    }

    private PipeTransport.PipeHandlers handlers() {
        var current = handlers;
        if (current == null) {
            throw new IllegalStateException("The transport never opened the pipe");
        }
        return current;
    }

    @Override
    public void write(byte[] data) {
        written.add(new String(data, StandardCharsets.UTF_8));
    }

    @Override
    public void closeStdin() {
        stdinClosed = true;
        if (exitWhenStdinCloses) {
            exited.complete(null);
        }
    }

    @Override
    public void close() {
        closed = true;
        // A pipe settles `exited` even when it is killed.
        exited.complete(null);
    }

    @Override
    public CompletableFuture<?> exited() {
        return exited;
    }
}
