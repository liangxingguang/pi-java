package com.pijava.agent.session;

/** Options for {@link SessionSearch#search(SessionSearchOptions)}. */
public record SessionSearchOptions(String text, String cwd) {

    /** Search all cwd directories. */
    public static SessionSearchOptions all(String text) {
        return new SessionSearchOptions(text, null);
    }
}