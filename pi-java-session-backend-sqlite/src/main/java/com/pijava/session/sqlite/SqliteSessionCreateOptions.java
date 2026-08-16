package com.pijava.session.sqlite;

import java.util.Map;

/** Create options for {@link SqliteSessionRepository}. */
public record SqliteSessionCreateOptions(
    String id,
    String cwd,
    String parentSessionId,
    Map<String, Object> metadata
) {

    /** Defaults: fresh id, current cwd. */
    public static SqliteSessionCreateOptions defaults() {
        return new SqliteSessionCreateOptions(null, System.getProperty("user.dir"), null, null);
    }
}
