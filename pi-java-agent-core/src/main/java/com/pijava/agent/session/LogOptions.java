package com.pijava.agent.session;

/**
 * Options for {@link SessionStorage#getLog(LogOptions)}. {@code null}
 * {@code afterSeq} disables the lower bound; {@code null} {@code limit} is
 * unlimited.
 */
public record LogOptions(Long afterSeq, Integer limit) {

    /** The full log. */
    public static LogOptions none() {
        return new LogOptions(null, null);
    }
}
