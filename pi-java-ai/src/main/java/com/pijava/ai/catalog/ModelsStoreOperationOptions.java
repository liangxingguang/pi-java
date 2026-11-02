package com.pijava.ai.catalog;

import com.pijava.ai.AbortSignal;

/**
 * Per-operation options for {@link ModelsStore} (pi {@code models-store.ts:17-19}).
 *
 * @param signal cancellation signal; {@code null} means no cancellation
 */
public record ModelsStoreOperationOptions(AbortSignal signal) {

    /** No signal. */
    public static final ModelsStoreOperationOptions NONE = new ModelsStoreOperationOptions(null);

    /** Throws {@link com.pijava.ai.AbortedException} when the signal has fired. */
    public void throwIfAborted() {
        if (signal != null) {
            signal.throwIfAborted();
        }
    }
}
