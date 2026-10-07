package com.pijava.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Handlers and listener subscriptions of an {@link McpClient}
 * (client.ts:165-166,172-288).
 */
final class McpClientRegistrations {

    private final Map<String, RequestHandler> requestHandlers = new java.util.HashMap<>();
    private final Map<String, List<Consumer<Object>>> notificationListeners =
            new java.util.HashMap<>();
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();

    /** Register (replace) a handler for a server request (client.ts:262-267). */
    Runnable setRequestHandler(String method, RequestHandler handler) {
        synchronized (this) {
            requestHandlers.put(method, handler);
        }
        return () -> {
            synchronized (this) {
                if (requestHandlers.get(method) == handler) {
                    requestHandlers.remove(method);
                }
            }
        };
    }

    /** Look up a handler, or {@code null}. */
    @Nullable RequestHandler requestHandler(String method) {
        synchronized (this) {
            return requestHandlers.get(method);
        }
    }

    /** Subscribe to a notification (client.ts:269-277). */
    Runnable onNotification(String method, Consumer<Object> listener) {
        synchronized (this) {
            notificationListeners.computeIfAbsent(method, key -> new CopyOnWriteArrayList<>())
                    .add(listener);
        }
        return () -> {
            synchronized (this) {
                var list = notificationListeners.get(method);
                if (list != null) {
                    list.remove(listener);
                    if (list.isEmpty()) {
                        notificationListeners.remove(method);
                    }
                }
            }
        };
    }

    /** Snapshot of the listeners of one method. */
    List<Consumer<Object>> notificationListenerSnapshot(String method) {
        synchronized (this) {
            var list = notificationListeners.get(method);
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    /** Subscribe to client errors (client.ts:279-282). */
    Runnable onError(Consumer<Throwable> listener) {
        errorListeners.add(listener);
        return () -> errorListeners.remove(listener);
    }

    /** Report one error to every error listener. */
    void reportError(Throwable error) {
        for (var listener : errorListeners) {
            listener.accept(error);
        }
    }

    /** Subscribe once to close (client.ts:284-288). */
    Runnable onClose(Runnable listener) {
        closeListeners.add(listener);
        return () -> closeListeners.remove(listener);
    }

    /** Notify every close listener. */
    void reportClose() {
        for (var listener : closeListeners) {
            listener.run();
        }
    }
}
