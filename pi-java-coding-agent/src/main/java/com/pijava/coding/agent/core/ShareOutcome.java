package com.pijava.coding.agent.core;

/** Terminal result of a session-share attempt. */
public record ShareOutcome(Kind kind, String message) {

    /** Whether sharing completed, failed, or was cancelled. */
    public enum Kind { SUCCESS, ERROR, CANCELLED }
}
