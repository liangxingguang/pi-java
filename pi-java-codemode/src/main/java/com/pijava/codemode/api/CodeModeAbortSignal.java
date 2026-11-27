package com.pijava.codemode.api;

/**
 * Minimal cancellation signal for one script execution.
 *
 * <p>Mirrors the part of the TypeScript {@code AbortSignal} the codemode
 * runtime consumes, without depending on the agent module.</p>
 */
public interface CodeModeAbortSignal {
    /** @return whether execution was cancelled */
    boolean isAborted();

    /**
     * Runs {@code listener} once when cancelled, immediately when already cancelled.
     *
     * @param listener cancellation callback
     * @return unsubscribe handle
     */
    Runnable onAbort(Runnable listener);
}
