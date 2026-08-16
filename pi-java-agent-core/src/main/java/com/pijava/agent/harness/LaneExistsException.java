package com.pijava.agent.harness;

/** Thrown when attempting to create a lane with an existing name. */
public final class LaneExistsException extends IllegalArgumentException {
    /** @param name the duplicate lane name */
    public LaneExistsException(String name) {
        super("Lane already exists: " + name);
    }
}
