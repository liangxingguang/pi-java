package com.pijava.mcp.transport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Listener bookkeeping for transports (transport.ts:20-54).
 */
public abstract class AbstractMcpTransport implements McpTransport {

    /** Default maximum message size: 16 MiB. */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final List<Consumer<Object>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();

    /** Deliver one parsed JSON value to every message listener. */
    protected void emitMessage(Object message) {
        for (var listener : messageListeners) {
            listener.accept(message);
        }
    }

    /** Report one error to every error listener. */
    protected void emitError(Throwable error) {
        for (var listener : errorListeners) {
            listener.accept(error);
        }
    }

    /** Notify every close listener. */
    protected void emitClose() {
        for (var listener : closeListeners) {
            listener.run();
        }
    }

    @Override
    public Runnable onMessage(Consumer<Object> listener) {
        messageListeners.add(listener);
        return () -> messageListeners.remove(listener);
    }

    @Override
    public Runnable onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
        return () -> errorListeners.remove(listener);
    }

    @Override
    public Runnable onClose(Runnable listener) {
        closeListeners.add(listener);
        return () -> closeListeners.remove(listener);
    }
}
