package com.pijava.ai.http;

/**
 * Exception thrown by {@link PiHttpClient} when an HTTP request fails
 * after exhausting all retry attempts.
 */
public final class PiHttpException extends RuntimeException {

    private final int statusCode;

    /**
     * 错误响应体原文（B135/B141 包）：Mistral 车道按 pi {@code formatMistralError}
     * 透传 body；永不 {@code null}（缺席为 {@code ""}）。<b>异常 {@link #getMessage()}
     * 措辞不因它改变</b> —— pi-messages 车道仍产 {@code "HTTP N"}。
     */
    private final String body;

    /**
     * Create a new exception.
     *
     * @param statusCode HTTP status code, or 0 for non-HTTP errors
     * @param message    descriptive message
     */
    public PiHttpException(int statusCode, String message) {
        this(statusCode, message, "");
    }

    /**
     * Create a new exception carrying the raw error response body.
     *
     * @param statusCode HTTP status code, or 0 for non-HTTP errors
     * @param message    descriptive message
     * @param body       raw response body (null is normalized to {@code ""})
     */
    public PiHttpException(int statusCode, String message, String body) {
        super(message);
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
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
        this.body = "";
    }

    /** HTTP status code, or 0 if not an HTTP error. */
    public int statusCode() {
        return statusCode;
    }

    /** Raw error response body, or {@code ""} when none was captured. */
    public String body() {
        return body;
    }
}
