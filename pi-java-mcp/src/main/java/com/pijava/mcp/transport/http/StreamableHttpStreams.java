package com.pijava.mcp.transport.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * SSE stream lifecycle for {@link StreamableHttpTransport}
 * (streamable-http.ts:323-500): response-stream resumption and the persistent
 * server-to-client GET stream.
 */
final class StreamableHttpStreams {

    /** Resume state: last event id, server-advertised retry, activity flag. */
    private static final class Cursor {
        @Nullable String lastEventId;
        @Nullable Long retryMs;
        boolean received;
    }

    private static final long DEFAULT_INITIAL_DELAY_MS = 1_000;
    private static final long DEFAULT_MAX_DELAY_MS = 30_000;
    private static final int DEFAULT_MAX_RETRIES = 5;

    private final StreamableHttpTransport transport;

    StreamableHttpStreams(StreamableHttpTransport transport) {
        this.transport = transport;
    }

    // ------------------------------------------------- response stream

    /** Read the SSE answer to one request, resuming when ids were assigned (:353-399). */
    void consumeResponseStream(java.io.InputStream body, Object requestId) {
        var cursor = new Cursor();
        var answered = new boolean[1];
        Consumer<Map<String, Object>> onMessage = message -> {
            if (isJsonRpcResponse(message) && Objects.equals(message.get("id"), requestId)) {
                answered[0] = true;
            }
        };

        int attempt = 0;
        @Nullable InputStream stream = body;
        @Nullable Exception failure = null;
        while (!transport.isClosed() && !answered[0]) {
            if (stream != null) {
                try {
                    consume(stream, cursor, onMessage);
                    failure = null;
                } catch (Exception error) {
                    failure = error;
                }
            }
            if (answered[0] || transport.isClosed()) {
                return;
            }
            if (failure != null && !isRetryable(failure)) {
                break;
            }
            // Without server-assigned ids the stream cannot be resumed.
            if (cursor.lastEventId == null || attempt >= maxRetries()) {
                break;
            }
            if (cursor.received) {
                attempt = 0;
            }
            cursor.received = false;
            if (!sleep(reconnectDelay(attempt++, cursor.retryMs))) {
                return;
            }

            try {
                var resumed = openSseStream(cursor.lastEventId);
                if (resumed == null) {
                    break;
                }
                stream = resumed.body();
            } catch (Exception openError) {
                if (!isRetryable(openError)) {
                    break;
                }
                stream = null;
            }
        }

        if (transport.isClosed()) {
            return;
        }
        var reason = failure == null
                ? "stream ended without a response"
                : String.valueOf(failure.getMessage());
        emitSyntheticError(requestId, reason);
    }

    private void emitSyntheticError(Object requestId, String reason) {
        var error = new LinkedHashMap<String, Object>();
        error.put("code", -32603);
        error.put("message", "MCP response stream failed: " + reason);
        var frame = new LinkedHashMap<String, Object>();
        frame.put("jsonrpc", "2.0");
        frame.put("id", requestId);
        frame.put("error", error);
        transport.deliver(frame);
    }

    // ------------------------------------------------------ GET stream

    /** Keep the server-to-client stream open, reconnecting with backoff (:407-431). */
    void runGetStream() {
        var cursor = new Cursor();
        int attempt = 0;
        while (!transport.isClosed()) {
            McpHttpResponse response;
            try {
                response = openSseStream(cursor.lastEventId);
            } catch (Exception error) {
                if (!isRetryable(error)) {
                    transport.fail(error);
                    return;
                }
                response = null;
            }
            if (response != null) {
                long openedAt = System.currentTimeMillis();
                try {
                    consume(response.body(), cursor, null);
                    if (cursor.received || System.currentTimeMillis() - openedAt > maxDelay()) {
                        attempt = 0;
                    }
                } catch (Exception error) {
                    if (!isRetryable(error)) {
                        transport.fail(error);
                        return;
                    }
                }
            }
            cursor.received = false;
            if (attempt >= maxRetries()) {
                transport.fail(new IOException(
                        "MCP server-to-client stream dropped and could not be reopened"));
                return;
            }
            if (!sleep(reconnectDelay(attempt++, cursor.retryMs))) {
                return;
            }
        }
    }

    /** Open a GET SSE stream; 405 means the server offers none (:442-460). */
    private @Nullable McpHttpResponse openSseStream(@Nullable String lastEventId) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "text/event-stream");
        if (lastEventId != null) {
            headers.put("Last-Event-ID", lastEventId);
        }
        var response = transport.authorizedFetch("GET", headers, null);
        if (response.status() == 405) {
            response.body().close();
            return null;
        }
        transport.checkResponsePublic(response);
        transport.captureSessionPublic(response);
        if (!"text/event-stream".equals(response.contentType())) {
            response.body().close();
            throw new McpHttpError(response.status(),
                    "Unsupported MCP GET response content type: " + response.contentType());
        }
        return response;
    }

    // --------------------------------------------------------- SSE pump

    private void consume(java.io.InputStream body, Cursor cursor,
                         @Nullable Consumer<Map<String, Object>> onMessage) throws Exception {
        SseStreamConsumer.consume(body, transport.maxMessageBytes(), new SseStreamConsumer.Handler() {
                @Override
                public void onEvent(SseEvent event) {
                    cursor.received = true;
                    // Data-less events prime resumption; non-message events are not JSON-RPC.
                    if (event.data().isBlank()
                            || (event.event() != null && !"message".equals(event.event()))) {
                        return;
                    }
                    Object raw;
                    try {
                        raw = com.pijava.mcp.McpJson.mapper()
                                .readValue(event.data(), Object.class);
                    } catch (IOException parseError) {
                        transport.fail(parseError);
                        return;
                    }
                    if (raw instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        var message = (Map<String, Object>) map;
                        if (onMessage != null) {
                            onMessage.accept(message);
                        }
                        transport.deliver(message);
                    }
                }

                @Override
                public void onId(String id) {
                    cursor.lastEventId = id;
                }

                @Override
                public void onRetry(long retryMs) {
                    cursor.retryMs = retryMs;
                }
            });
    }

    // ------------------------------------------------------- retry rules

    /** Network failures and transient statuses are retried (:481-487). */
    private static boolean isRetryable(Exception error) {
        if (error instanceof McpHttpError httpError) {
            var status = httpError.status();
            return status == 408 || status == 429 || status >= 500;
        }
        return error instanceof IOException;
    }

    private long reconnectDelay(int attempt, @Nullable Long serverDelayMs) {
        if (serverDelayMs != null) {
            return serverDelayMs;
        }
        var initial = transport.transportOptions().reconnect() == null
                ? DEFAULT_INITIAL_DELAY_MS
                : transport.transportOptions().reconnect().initialDelayMs();
        return Math.min((long) (initial * Math.pow(2, attempt)), maxDelay());
    }

    private long maxDelay() {
        var reconnect = transport.transportOptions().reconnect();
        return reconnect == null ? DEFAULT_MAX_DELAY_MS : reconnect.maxDelayMs();
    }

    private int maxRetries() {
        var reconnect = transport.transportOptions().reconnect();
        return reconnect == null ? DEFAULT_MAX_RETRIES : reconnect.maxRetries();
    }

    /** Sleep the delay; false when aborted (:489-500). Virtual threads are daemon. */
    private boolean sleep(long ms) {
        var done = new CountDownLatch(1);
        var unsubscribe = transport.abortSignal().onAbort(done::countDown);
        try {
            return done.await(Math.max(1, ms), TimeUnit.MILLISECONDS) == false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            unsubscribe.run();
        }
    }

    private static boolean isJsonRpcResponse(Map<String, Object> message) {
        return message.containsKey("id")
                && (message.containsKey("result") || message.containsKey("error"))
                && !message.containsKey("method");
    }
}
