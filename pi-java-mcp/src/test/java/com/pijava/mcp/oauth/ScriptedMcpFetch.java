package com.pijava.mcp.oauth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpFetch;

/**
 * Scripted {@link McpFetch}: queues one or more responses per request URL
 * (keyed by full URL), records request order, methods, bodies and last
 * headers. Unknown URLs answer 404, matching a server that hosts nothing there.
 */
final class ScriptedMcpFetch implements McpFetch {

    /** One request as observed by the script. */
    record Sent(String method, URI url, Map<String, String> headers, @Nullable String body) {
    }

    private final Map<String, Deque<Fetched>> responses = new LinkedHashMap<>();
    private final List<Sent> sent = new ArrayList<>();
    private @Nullable URI failUrl;
    private @Nullable Map<String, String> lastHeaders;

    /** Queue a response for the given URL. */
    ScriptedMcpFetch reply(String url, int status, String body) {
        return enqueue(url, new Fetched(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Queue an empty-body response. */
    ScriptedMcpFetch status(String url, int status) {
        return enqueue(url, new Fetched(status, new byte[0]));
    }

    /** Queue a JSON response (alias of {@link #reply}). */
    ScriptedMcpFetch json(String url, int status, String body) {
        return reply(url, status, body);
    }

    /** Network failure (pi's TypeError) whenever this exact URL is requested. */
    ScriptedMcpFetch networkFails(String url) {
        this.failUrl = URI.create(url);
        return this;
    }

    private ScriptedMcpFetch enqueue(String url, Fetched fetched) {
        responses.computeIfAbsent(url, key -> new ArrayDeque<>()).add(fetched);
        return this;
    }

    @Override
    public Fetched fetch(Request request) throws IOException {
        sent.add(new Sent(request.method(), request.url(), Map.copyOf(request.headers()),
                request.body() == null ? null : new String(request.body(), StandardCharsets.UTF_8)));
        lastHeaders = Map.copyOf(request.headers());
        if (failUrl != null && failUrl.toString().equals(request.url().toString())) {
            throw new IOException("connect failed");
        }
        var queue = responses.get(request.url().toString());
        if (queue == null || queue.isEmpty()) {
            return new Fetched(404, new byte[0]);
        }
        return queue.poll();
    }

    /** All requested URLs, in order. */
    List<URI> requested() {
        return sent.stream().map(Sent::url).toList();
    }

    /** All requests, in order. */
    List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** Requests to one URL, in order. */
    List<Sent> sentTo(String url) {
        return sent.stream().filter(item -> item.url().toString().equals(url)).toList();
    }

    /** Headers of the last request. */
    @Nullable Map<String, String> lastHeaders() {
        return lastHeaders;
    }
}
