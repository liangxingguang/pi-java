package com.pijava.agent.harness;

/** Thrown when compaction is requested on a lane with nothing to compact. */
public final class NothingToCompactException extends IllegalStateException {
    /** @param laneName the lane with nothing to compact */
    public NothingToCompactException(String laneName) {
        super("Nothing to compact in lane: " + laneName);
    }
}
