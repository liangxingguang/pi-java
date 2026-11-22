package com.pijava.mcp.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpJson;
import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;

/**
 * MCP's stdio transport over a program that something else runs — for example on another machine
 * through an environment (pi {@code transports/pipe.ts}).
 *
 * <p>The transport closes when the program ends; an {@code exited} that failed without
 * {@link #close()} having caused it is reported as an error first. Framing is the stdio one
 * ({@link StdioLineFrames.Framer}).</p>
 */
public final class PipeTransport extends AbstractMcpTransport {

    /** Grace after closing stdin before the program is killed (pi {@code DEFAULT_CLOSE_TIMEOUT_MS}). */
    public static final long DEFAULT_CLOSE_TIMEOUT_MS = 2_000;

    /** A running program's stdio, as an environment provides it (pi {@code McpPipe}). */
    public interface McpPipe {

        /** Write bytes to the program's stdin. */
        void write(byte[] data) throws Exception;

        /** Close the program's stdin, asking it to exit. */
        void closeStdin();

        /** Kill the program and what it started, and stop reading it. */
        void close();

        /**
         * Settles when the program has ended, whatever the reason; it must also settle after
         * {@link #close()}.
         *
         * @return the completion of the program's end
         */
        CompletableFuture<?> exited();
    }

    /**
     * Where an opened pipe's output goes (pi {@code McpPipeHandlers}).
     *
     * @param onStdout the program's stdout bytes
     * @param onStderr the program's stderr bytes
     */
    public record PipeHandlers(Consumer<byte[]> onStdout, Consumer<byte[]> onStderr) {
    }

    /** Starts the program behind a {@link PipeTransport} (pi {@code open}). */
    @FunctionalInterface
    public interface PipeOpener {

        /**
         * Start the program.
         *
         * @param handlers where its output goes from then on
         * @return the running program
         * @throws Exception when it could not be started
         */
        McpPipe open(PipeHandlers handlers) throws Exception;
    }

    /**
     * Options of a {@link PipeTransport} (pi {@code PipeTransportOptions}).
     *
     * @param open starts the program
     * @param maxMessageBytes maximum frame size; {@code <=0} uses the default
     * @param onStderr every stderr chunk, decoded as UTF-8, or {@code null}
     * @param closeTimeoutMs shutdown grace period in ms; {@code <=0} uses the default
     */
    public record Options(PipeOpener open, int maxMessageBytes,
                          @Nullable Consumer<String> onStderr, long closeTimeoutMs) {

        /** Minimal options: default frame size and grace period, no stderr listener. */
        public Options(PipeOpener open) {
            this(open, 0, null, 0);
        }
    }

    private final Options options;
    private final StderrTail stderrTail = new StderrTail();
    private final StdioLineFrames.Framer framer;
    private final Object lock = new Object();
    private volatile @Nullable McpPipe pipe;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean ended;

    /** Create a transport. */
    public PipeTransport(Options options) {
        this.options = options;
        int maxMessageBytes = options.maxMessageBytes() > 0
                ? options.maxMessageBytes() : DEFAULT_MAX_MESSAGE_BYTES;
        this.framer = new StdioLineFrames.Framer(maxMessageBytes, this::deliver, this::emitError);
    }

    /** The last 64 KiB of the program's stderr. */
    public String stderr() {
        return stderrTail.text();
    }

    @Override
    public void start() throws Exception {
        synchronized (lock) {
            if (started) {
                throw new IOException("MCP pipe transport already started");
            }
            if (closed) {
                throw new McpConnectionClosedError();
            }
            started = true;
        }
        var opened = options.open().open(new PipeHandlers(this::readStdout, this::readStderr));
        synchronized (lock) {
            if (closed) {
                // close() ran while the program was starting and found nothing to shut down.
                // Nobody else awaits `exited`, and a pipe may reject it after a kill.
                opened.exited().exceptionally(ignored -> null);
                opened.close();
                throw new McpConnectionClosedError();
            }
            pipe = opened;
        }
        opened.exited().whenComplete((value, failure) -> {
            if (failure == null) {
                exit();
            } else {
                fail(failure);
            }
        });
    }

    @Override
    public void send(Map<String, Object> message) throws Exception {
        McpPipe current;
        synchronized (lock) {
            current = pipe;
            if (current == null || closed || ended) {
                throw new McpConnectionClosedError();
            }
        }
        var bytes = McpJson.mapper().writeValueAsBytes(message);
        // The newline that frames the message.
        var framed = Arrays.copyOf(bytes, bytes.length + 1);
        framed[bytes.length] = '\n';
        current.write(framed);
    }

    @Override
    public void close() {
        McpPipe current;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            current = pipe;
        }
        if (current == null) {
            emitClose();
            return;
        }
        if (ended) {
            return;
        }
        // Shutdown per the spec: close stdin and let the program exit, then kill it.
        current.closeStdin();
        if (exitedWithin(current, closeTimeoutMs())) {
            return;
        }
        current.close();
        awaitQuietly(current);
    }

    private void readStdout(byte[] chunk) {
        framer.feed(chunk, chunk.length);
    }

    private void readStderr(byte[] chunk) {
        stderrTail.append(chunk);
        var onStderr = options.onStderr();
        if (onStderr != null) {
            onStderr.accept(new String(chunk, StandardCharsets.UTF_8));
        }
    }

    private void deliver(byte[] line) {
        try {
            emitMessage(McpJson.mapper().readValue(line, Object.class));
        } catch (IOException error) {
            emitError(error);
        }
    }

    /** The program ended: report an unfinished message, then close. */
    private void exit() {
        ended = true;
        framer.end();
        emitClose();
    }

    /** {@code exited} failed: an error unless {@link #close()} caused it, then the same as an exit. */
    private void fail(Throwable failure) {
        if (!closed) {
            emitError(failure);
        }
        exit();
    }

    private long closeTimeoutMs() {
        return options.closeTimeoutMs() > 0 ? options.closeTimeoutMs() : DEFAULT_CLOSE_TIMEOUT_MS;
    }

    /** Whether the program ended within the grace period, whatever the reason. */
    private static boolean exitedWithin(McpPipe pipe, long timeoutMs) {
        try {
            pipe.exited().handle((value, failure) -> true).get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException stillRunning) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException impossible) {
            // `handle` turns every failure into true.
            return true;
        }
    }

    private static void awaitQuietly(McpPipe pipe) {
        try {
            pipe.exited().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Killing is best effort; the pipe settles on its own.
        }
    }
}
