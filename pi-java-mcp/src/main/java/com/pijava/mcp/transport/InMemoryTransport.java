package com.pijava.mcp.transport;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;

/**
 * Bidirectional in-process transport (pi {@code in-memory.ts:1-51}).
 *
 * <p>Useful for tests and for driving an MCP client and server in one process
 * without a transport: {@link InMemoryTransports#create()} returns a connected
 * pair. Both sides must be started before messages flow.</p>
 *
 * <p>Messages are delivered on a new virtual thread, mirroring the
 * {@code queueMicrotask} semantics: {@code send} never re-enters the peer's
 * listeners on the calling stack.</p>
 */
public final class InMemoryTransport extends AbstractMcpTransport {

    /** Create an unconnected transport. */
    public InMemoryTransport() {
    }

    private @Nullable InMemoryTransport peer;
    private volatile boolean started;
    private volatile boolean closed;

    /** Connect two transports as peers. */
    public void connect(InMemoryTransport other) {
        if (peer != null || other.peer != null) {
            throw new IllegalStateException("transport already connected");
        }
        peer = other;
        other.peer = this;
    }

    @Override
    public void start() {
        if (closed) {
            throw new McpConnectionClosedError();
        }
        started = true;
    }

    @Override
    public void send(Map<String, Object> message) {
        var target = peer;
        if (!started || closed) {
            throw new McpConnectionClosedError();
        }
        if (target == null || !target.started || target.closed) {
            throw new McpConnectionClosedError("In-memory MCP peer is not connected");
        }
        var copy = deepCopy(message);
        Thread.startVirtualThread(() -> target.deliver(copy));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        emitClose();
        // Closing one side closes the pair, so a client that closes its transport
        // does not leave a peer listening (in-memory.ts:37-42).
        var target = peer;
        if (target != null) {
            target.close();
        }
    }

    /** Whether this side has been closed, by itself or through its peer. */
    public boolean isClosed() {
        return closed;
    }

    /** Exposed so tests can simulate transport-level failures ({@code in-memory.ts:44-46}). */
    @Override
    public void emitError(Throwable error) {
        super.emitError(error);
    }

    private void deliver(Object message) {
        if (!closed) {
            emitMessage(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> message) {
        var mapper = com.pijava.mcp.McpJson.mapper();
        return mapper.convertValue(message, Map.class);
    }
}
