package com.pijava.ai;

/**
 * A cancellation signal wrapping a volatile boolean flag.
 *
 * <p>Shared by the HTTP client (to cancel in-flight requests) and the agent
 * runtime (to cancel runs and tool execution). Aligned with pi's
 * {@code AbortSignal}.</p>
 */
public class AbortSignal {
    private volatile boolean aborted;

    /** Check whether the signal has been triggered. */
    public boolean isAborted() { return aborted; }

    /** Trigger the abort signal. */
    public void abort() { aborted = true; }

    /** Create a fresh signal. */
    public static AbortSignal create() { return new AbortSignal(); }
}
