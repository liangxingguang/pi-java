package com.pijava.agent.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Default implementation of {@link WatchHandle}.
 * Holds a current-value supplier and a list of update listeners.
 */
final class DefaultWatchHandle<T> implements WatchHandle<T> {
    private final Supplier<T> currentSupplier;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private Runnable onClose = () -> { };

    DefaultWatchHandle(Supplier<T> currentSupplier) {
        this.currentSupplier = currentSupplier;
    }

    @Override
    public T current() {
        return currentSupplier.get();
    }

    @Override
    public WatchHandle<T> subscribe(Consumer<T> listener) {
        listeners.add(listener);
        return this;
    }

    /** Set the action to run on close (unsubscribe from the event bus). */
    void onClose(Runnable action) {
        this.onClose = action;
    }

    @Override
    public void close() {
        onClose.run();
    }

    /** Notify all listeners of a new snapshot. */
    void notify(T snapshot) {
        for (var listener : listeners) {
            listener.accept(snapshot);
        }
    }
}
