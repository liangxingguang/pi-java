package com.pijava.ai;

/**
 * Thrown by {@link AbortSignal#throwIfAborted()} when the signal has fired.
 *
 * <p>Corresponds to pi's {@code DOMException} with name {@code "AbortError"}. Java has no
 * DOM exception type, so this is a plain runtime exception with the same message.</p>
 */
public final class AbortedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Fixed message matching the JS abort reason. */
    public AbortedException() {
        super("This operation was aborted");
    }
}
