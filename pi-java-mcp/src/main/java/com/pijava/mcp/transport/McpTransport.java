package com.pijava.mcp.transport;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A transport over which JSON-RPC messages travel.
 *
 * <p>Messages are wire maps ({@code jsonrpc/id/method/params} or
 * {@code result/error}); the client builds them with the raw id so an integer
 * id stays {@code 1}, never {@code 1.0}.</p>
 */
public interface McpTransport {

    /** Start the transport. */
    void start() throws Exception;

    /** Send one JSON-RPC wire message. */
    void send(Map<String, Object> message) throws Exception;

    /** Close the transport. */
    void close() throws Exception;

    /** Subscribe to parsed JSON values; returns an unsubscribe handle. */
    Runnable onMessage(Consumer<Object> listener);

    /** Subscribe to transport errors; returns an unsubscribe handle. */
    Runnable onError(Consumer<Throwable> listener);

    /** Subscribe to transport close; returns an unsubscribe handle. */
    Runnable onClose(Runnable listener);

    /** Record the protocol version the server selected. Default: no-op. */
    default void setProtocolVersion(String version) {
    }
}
