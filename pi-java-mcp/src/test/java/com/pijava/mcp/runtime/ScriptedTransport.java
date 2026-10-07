package com.pijava.mcp.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.mcp.transport.AbstractMcpTransport;
import com.pijava.mcp.transport.McpTransport;

/**
 * One connection onto a {@link ScriptedServer}. A reconnect gets a new instance, the way a real
 * transport factory does.
 */
final class ScriptedTransport extends AbstractMcpTransport implements McpTransport {

    private final ScriptedServer server;
    private volatile boolean started;
    private volatile boolean closed;

    ScriptedTransport(ScriptedServer server) {
        this.server = server;
    }

    @Override
    public void start() {
        if (started) {
            throw new IllegalStateException("transport already started");
        }
        started = true;
    }

    @Override
    public void send(Map<String, Object> message) {
        if (closed) {
            throw new IllegalStateException("transport closed");
        }
        server.onSend(message, this);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        emitClose();
    }

    /** Hand one server-to-client message to the client. */
    void deliver(Object message) {
        emitMessage(message);
    }

    /** Answer one of the client's requests. */
    void reply(Object id, Map<String, Object> body) {
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.putAll(body);
        emitMessage(message);
    }
}
