package com.pijava.agent.harness;

/** Thrown when an operation is attempted on a closed harness. */
public final class HarnessClosedException extends IllegalStateException {
    /** Creates the exception with a standard message. */
    public HarnessClosedException() {
        super("AgentHarness is closed");
    }
}
