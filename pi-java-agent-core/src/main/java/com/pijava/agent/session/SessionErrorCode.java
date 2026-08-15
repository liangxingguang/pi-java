package com.pijava.agent.session;

/** Session storage error codes (the 8 pi {@code SessionErrorCode} literals). */
public enum SessionErrorCode {
    NOT_FOUND("not_found"),
    ALREADY_EXISTS("already_exists"),
    INVALID_ENTRY("invalid_entry"),
    INVALID_PAYLOAD("invalid_payload"),
    INVALID_LANE("invalid_lane"),
    INVALID_QUERY("invalid_query"),
    INVALID_FORK_TARGET("invalid_fork_target"),
    STORAGE("storage");

    private final String value;

    SessionErrorCode(String value) {
        this.value = value;
    }

    /** The snake_case literal (aligned with pi). */
    public String value() {
        return value;
    }
}