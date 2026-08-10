package com.pijava.ai.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * Thin wrapper around JDK {@link java.net.http.HttpClient} with SSE parsing,
 * automatic retry, and abort-signal support.
 *
 * <p>Only Mistral directly uses this in Phase 1, but it is designed for reuse
 * by any future provider that lacks a dedicated SDK.</p>
 *
 * <p>Instances are created via {@link #builder()} and are immutable and
 * thread-safe after construction.</p>
 */
public final class PiHttpClient implements AutoCloseable {

    /** A single Server-Sent Events data line. */
    public record ServerSentEvent(String id, String event, String data) {

        /** An empty event sentinel. */
        public static final ServerSentEvent EMPTY = new ServerSentEvent("", "", "");
    }

    private final HttpClient http;
    private final String userAgent;
    private final RetryPolicy retryPolicy;
    private final Duration connectTimeout;
    private final java.net.ProxySelector proxy;

    private PiHttpClient(Builder builder) {
        var httpBuilder = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (builder.proxy != null) {
            httpBuilder.proxy(builder.proxy);
        }
        this.http = httpBuilder.build();
        this.userAgent = builder.userAgent;
        this.retryPolicy = builder.retryPolicy;
        this.connectTimeout = builder.connectTimeout;
        this.proxy = builder.proxy;
    }

    // ── Public API ─────────────────────────────────────────────

    /**
     * POST JSON to {@code url} and consume the response as SSE events.
     *
     * @param url      the target endpoint
     * @param jsonBody the JSON request body
     * @param headers  additional HTTP headers
     * @return a lazy iterator of SSE events
     */
    public Iterator<ServerSentEvent> postSse(String url, String jsonBody,
                                              Map<String, String> headers) {
        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(300));

        if (headers != null) {
            headers.forEach(requestBuilder::header);
        }

        var request = requestBuilder.build();
        return executeWithRetrySse(request, 0);
    }

    /**
     * Send a generic HTTP request and return the string response.
     *
     * @param request the HTTP request to send
     * @return the response body as a string
     */
    public HttpResponse<String> send(HttpRequest request) throws java.io.IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Request interrupted", e);
        }
    }

    /** Create a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void close() {
        // HttpClient is effectively stateless; no explicit cleanup needed.
    }

    // ── Retry / SSE internals ──────────────────────────────────

    private Iterator<ServerSentEvent> executeWithRetrySse(HttpRequest request, int attempt) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            int status = response.statusCode();

            if (retryPolicy.shouldRetry(status) && attempt < retryPolicy.maxRetries()) {
                long delayMs = retryPolicy.delayMs(status, attempt, response);
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                return executeWithRetrySse(request, attempt + 1);
            }

            if (status < 200 || status >= 300) {
                throw new PiHttpException(status, "HTTP " + status);
            }

            return new SseIterator(response.body().iterator());
        } catch (java.io.IOException e) {
            if (retryPolicy.shouldRetry(e) && attempt < retryPolicy.maxRetries()) {
                long delayMs = retryPolicy.delayMs(0, attempt, null);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PiHttpException(0, "Retry interrupted", e);
                    }
                }
                return executeWithRetrySse(request, attempt + 1);
            }
            throw new PiHttpException(0, "Request failed after " + (attempt + 1) + " attempts", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PiHttpException(0, "Request interrupted", e);
        }
    }

    // ── Builder ────────────────────────────────────────────────

    public static final class Builder {
        private String userAgent = "pi-java/dev";
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
        private Duration connectTimeout = Duration.ofSeconds(30);
        private java.net.ProxySelector proxy;

        public Builder userAgent(String ua) {
            this.userAgent = ua;
            return this;
        }

        public Builder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        public Builder proxy(java.net.ProxySelector proxy) {
            this.proxy = proxy;
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public PiHttpClient build() {
            return new PiHttpClient(this);
        }
    }

    // ── SSE Iterator ───────────────────────────────────────────

    private static final class SseIterator implements Iterator<ServerSentEvent> {
        private final Iterator<String> lines;
        private ServerSentEvent nextEvent;
        private boolean done;

        SseIterator(Iterator<String> lines) {
            this.lines = lines;
            advance();
        }

        @Override
        public boolean hasNext() {
            return !done && nextEvent != null;
        }

        @Override
        public ServerSentEvent next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            var current = nextEvent;
            advance();
            return current;
        }

        private void advance() {
            String id = "";
            String event = "";
            StringBuilder data = new StringBuilder();

            while (lines.hasNext()) {
                String line = lines.next();
                if (line == null) {
                    break;
                }
                if (line.isEmpty()) {
                    // Empty line = event boundary
                    if (!data.isEmpty()) {
                        nextEvent = new ServerSentEvent(id, event,
                                data.length() > 0 && data.charAt(data.length() - 1) == '\n'
                                        ? data.substring(0, data.length() - 1)
                                        : data.toString());
                        return;
                    }
                    // Continue gathering for next event
                    id = "";
                    event = "";
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    // Comment line — ignore
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).strip());
                }
                // Other fields are ignored
            }
            // End of stream
            done = true;
            if (!data.isEmpty()) {
                nextEvent = new ServerSentEvent(id, event,
                        data.charAt(data.length() - 1) == '\n'
                                ? data.substring(0, data.length() - 1)
                                : data.toString());
            } else {
                nextEvent = null;
            }
        }
    }
}
