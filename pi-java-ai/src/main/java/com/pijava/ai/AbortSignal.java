package com.pijava.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A cancellation signal wrapping a volatile boolean flag.
 *
 * <p>Shared by the HTTP client (to cancel in-flight requests) and the agent
 * runtime (to cancel runs and tool execution). Aligned with pi's
 * {@code AbortSignal}.</p>
 */
public class AbortSignal {
    private volatile boolean aborted;
    private final List<Runnable> abortListeners = new CopyOnWriteArrayList<>();

    /** Check whether the signal has been triggered. */
    public boolean isAborted() { return aborted; }

    /** Trigger the abort signal; listeners run once. */
    public void abort() {
        aborted = true;
        for (var listener : abortListeners) {
            listener.run();
        }
    }

    /**
     * Run {@code listener} once when aborted, immediately when the signal was
     * already triggered. Returns an unsubscribe handle.
     */
    public Runnable onAbort(Runnable listener) {
        if (aborted) {
            listener.run();
            return () -> { };
        }
        abortListeners.add(listener);
        return () -> abortListeners.remove(listener);
    }

    /** Create a fresh signal. */
    public static AbortSignal create() { return new AbortSignal(); }
}
