package com.pijava.agent.session.memory;

/** List options for {@link MemorySessionRepository}. */
public record MemorySessionListOptions(String cwd) {

    /** List all in-memory sessions. */
    public static MemorySessionListOptions all() {
        return new MemorySessionListOptions(null);
    }
}
