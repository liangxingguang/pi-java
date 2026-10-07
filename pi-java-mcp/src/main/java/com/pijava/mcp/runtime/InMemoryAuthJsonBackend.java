package com.pijava.mcp.runtime;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * {@link AuthJsonBackend} that keeps the content in a field
 * (pi {@code InMemoryAuthStorageBackend}, {@code auth-storage.ts:292-322}).
 */
public final class InMemoryAuthJsonBackend implements AuthJsonBackend {

    private final Object lock = new Object();
    private @Nullable String value;

    /** Create an empty store. */
    public InMemoryAuthJsonBackend() {
    }

    /**
     * Create a store with content already in it.
     *
     * @param initial the JSON text to start from
     */
    public InMemoryAuthJsonBackend(String initial) {
        this.value = initial;
    }

    @Override
    public <T> T withLock(Function<@Nullable String, LockResult<T>> edit) {
        synchronized (lock) {
            var outcome = edit.apply(value);
            if (outcome.next() != null) {
                value = outcome.next();
            }
            return outcome.result();
        }
    }

    /** The current content, for assertions. */
    public @Nullable String content() {
        synchronized (lock) {
            return value;
        }
    }
}
