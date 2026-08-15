package com.pijava.agent.session;

/** Session storage error carrying a {@link SessionErrorCode}. */
public class SessionError extends RuntimeException {

    private final SessionErrorCode code;

    public SessionError(SessionErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SessionError(SessionErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** The error code. */
    public SessionErrorCode code() {
        return code;
    }
}