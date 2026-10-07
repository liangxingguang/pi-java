package com.pijava.mcp.runtime;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Read-modify-write access to one JSON file, under a lock
 * (pi {@code core/auth-storage.ts:41-47}).
 *
 * <p>pi exposes an async entry point too, because its store is used from promise chains. The
 * MCP credential store only ever uses the synchronous one, so only that is ported.</p>
 */
public interface AuthJsonBackend {

    /**
     * Run {@code edit} against the file's current text and, when it returns a replacement,
     * write it back.
     *
     * @param edit receives the current content and returns the result to hand back plus the
     *             new content, or {@code null} to leave the file alone
     * @param <T> the value handed back to the caller
     * @return what {@code edit} returned
     */
    <T> T withLock(Function<@Nullable String, LockResult<T>> edit);

    /**
     * What one locked edit produces ({@code auth-storage.ts:19-22}).
     *
     * @param result the value handed back to the caller
     * @param next the file's new content, or {@code null} to leave it alone
     * @param <T> the value handed back to the caller
     */
    record LockResult<T>(T result, @Nullable String next) {

        /** An edit that leaves the file alone. */
        public static <T> LockResult<T> of(T result) {
            return new LockResult<>(result, null);
        }

        /** An edit that replaces the file. */
        public static <T> LockResult<T> writing(T result, String next) {
            return new LockResult<>(result, next);
        }
    }
}
