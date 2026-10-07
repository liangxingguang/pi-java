package com.pijava.mcp.transport.http;

/**
 * Reconnection policy for dropped SSE streams
 * (streamable-http.ts:105-113).
 *
 * @param initialDelayMs delay before the first attempt, unless the server sent retry
 * @param maxDelayMs     upper bound for exponential backoff
 * @param maxRetries     consecutive failed attempts before giving up
 */
record ReconnectOptions(long initialDelayMs, long maxDelayMs, int maxRetries) {
}
