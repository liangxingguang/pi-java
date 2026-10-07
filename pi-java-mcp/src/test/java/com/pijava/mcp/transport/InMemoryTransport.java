package com.pijava.mcp.transport;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;

/**
 * Bidirectional in-process transport for tests (in-memory.ts:10-43).
 *
 * <p>Messages are delivered on a new virtual thread, mirroring the
 * {@code queueMicrotask} semantics: {@code send} never re-enters the peer's
 * listeners on the calling stack.</p>
 */
final class InMemoryTransport extends AbstractMcpTransport {

    private @Nullable InMemoryTransport peer;
    private volatile boolean closed;

    /** Connect two transports as peers. */
    void connect(InMemoryTransport other) {
        if (peer != null || other.peer != null) {
            throw new IllegalStateException("transport already connected");
        }
        peer = other;
        other.peer = this;
    }

    @Override
    public void start() {
        // Ready immediately once paired.
    }

    @Override
    public void send(Map<String, Object> message) {
        var target = peer;
        if (closed || target == null) {
            throw new McpConnectionClosedError();
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
