package com.pijava.agent.harness;

import java.util.function.Consumer;

/**
 * Handle returned by {@code watch()} — allows subscribing to updates and unsubscribing.
 *
 * @param <T> the snapshot type (LaneSnapshot or SessionSnapshot)
 */
public interface WatchHandle<T> extends AutoCloseable {
    /** Get the current snapshot value. */
    T current();

    /** Register a callback invoked on each new snapshot. Returns this for chaining. */
    WatchHandle<T> subscribe(Consumer<T> listener);

    /** Unsubscribe from further updates. */
    @Override
    void close();
}
