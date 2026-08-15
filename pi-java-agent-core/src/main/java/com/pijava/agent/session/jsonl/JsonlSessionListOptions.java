package com.pijava.agent.session.jsonl;

/** List options for {@link JsonlSessionRepository}. */
public record JsonlSessionListOptions(String cwd) {

    /** List sessions for the current working directory. */
    public static JsonlSessionListOptions defaults() {
        return new JsonlSessionListOptions(System.getProperty("user.dir"));
    }

    /** List sessions across all cwd directories. */
    public static JsonlSessionListOptions all() {
        return new JsonlSessionListOptions(null);
    }
}