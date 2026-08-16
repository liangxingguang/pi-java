package com.pijava.session.sqlite;

/** List options for {@link SqliteSessionRepository}. */
public record SqliteSessionListOptions(String cwd) {

    /** List all sessions. */
    public static SqliteSessionListOptions all() {
        return new SqliteSessionListOptions(null);
    }
}
