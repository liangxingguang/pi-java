package com.pijava.coding.agent.core;

/** Cooperative cancellation checked between sharing stages. */
@FunctionalInterface
public interface ShareCancellation {

    /** Whether the current share has been cancelled. */
    boolean isCancelled();

    /** A cancellation token that never fires. */
    static ShareCancellation never() {
        return () -> false;
    }
}
