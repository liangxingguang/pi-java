package com.pijava.agent.session.memory;

/** Create options for {@link MemorySessionRepository}. */
public record MemorySessionCreateOptions(
    String id,
    String cwd,
    String parentSessionId
) {

    /** Defaults: fresh id, current cwd. */
    public static MemorySessionCreateOptions defaults() {
        return new MemorySessionCreateOptions(null, System.getProperty("user.dir"), null);
    }
}
