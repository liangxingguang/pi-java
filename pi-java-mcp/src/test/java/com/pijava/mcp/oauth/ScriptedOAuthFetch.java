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

/**
 * Scripted {@link OAuthFetch}: queues one or more responses per request URL
 * (keyed by full URL), records request order and last headers. Unknown URLs
 * answer 404, matching a server that hosts no metadata there.
 */
final class ScriptedOAuthFetch implements OAuthFetch {

    private final Map<String, Deque<Fetched>> responses = new LinkedHashMap<>();
    private final List<URI> requested = new ArrayList<>();
    private @Nullable URI failUrl;
    private @Nullable Map<String, String> lastHeaders;

    /** Queue a JSON response for the given URL. */
    ScriptedOAuthFetch json(String url, int status, String body) {
        return enqueue(url, new Fetched(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Queue an empty-body response. */
    ScriptedOAuthFetch status(String url, int status) {
        return enqueue(url, new Fetched(status, new byte[0]));
    }

    /** Network failure (TypeError in pi) whenever this exact URL is requested. */
    ScriptedOAuthFetch networkFails(String url) {
        this.failUrl = URI.create(url);
        return this;
    }

    private ScriptedOAuthFetch enqueue(String url, Fetched fetched) {
        responses.computeIfAbsent(url, key -> new ArrayDeque<>()).add(fetched);
        return this;
    }

    @Override
    public Fetched fetch(URI url, Map<String, String> headers) throws IOException {
        requested.add(url);
        lastHeaders = Map.copyOf(headers);
        if (failUrl != null && failUrl.toString().equals(url.toString())) {
            throw new IOException("connect failed");
        }
        var queue = responses.get(url.toString());
        if (queue == null || queue.isEmpty()) {
            return new Fetched(404, new byte[0]);
        }
        return queue.poll();
    }

    /** All requested URLs, in order. */
    List<URI> requested() {
        return List.copyOf(requested);
    }

    /** Headers of the last request. */
    @Nullable Map<String, String> lastHeaders() {
        return lastHeaders;
    }
}
