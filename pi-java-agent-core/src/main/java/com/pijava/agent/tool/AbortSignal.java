package com.pijava.agent.tool;

/**
 * Abort signal for tool cancellation. Wraps a volatile boolean flag.
 * Aligned with pi's {@code AbortSignal}.
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
