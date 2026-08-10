package com.pijava.ai.http;

/**
 * Exception thrown by {@link PiHttpClient} when an HTTP request fails
 * after exhausting all retry attempts.
 */
public final class PiHttpException extends RuntimeException {

    private final int statusCode;

    /**
     * Create a new exception.
     *
     * @param statusCode HTTP status code, or 0 for non-HTTP errors
     * @param message    descriptive message
     */
    public PiHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Create a new exception with a cause.
     *
     * @param statusCode HTTP status code, or 0 for non-HTTP errors
     * @param message    descriptive message
     * @param cause      underlying exception
     */
    public PiHttpException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status code, or 0 if not an HTTP error. */
    public int statusCode() {
        return statusCode;
    }
}
