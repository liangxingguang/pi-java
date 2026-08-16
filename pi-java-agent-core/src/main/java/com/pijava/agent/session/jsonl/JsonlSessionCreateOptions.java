package com.pijava.agent.session.jsonl;

import java.util.Map;

/** Create options for {@link JsonlSessionRepository}. */
public record JsonlSessionCreateOptions(
    String id,
    String cwd,
    String parentSessionId,
    Map<String, Object> metadata
) {

    /** Default options: fresh id, current cwd. */
    public static JsonlSessionCreateOptions defaults() {
        return new JsonlSessionCreateOptions(null, System.getProperty("user.dir"), null, null);
    }
}
