package com.pijava.mcp;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * {@link java.net.http.HttpClient}-backed {@link McpFetch}, the Java stand-in
 * for pi's default {@code globalThis.fetch}.
 */
public final class JdkMcpFetch implements McpFetch {

    private final HttpClient client;

    /** Create with a default client. */
    public JdkMcpFetch() {
        this(HttpClient.newHttpClient());
    }

    /** Create with an injected client. */
    public JdkMcpFetch(HttpClient client) {
        this.client = client;
    }

    @Override
    public Fetched fetch(Request request) throws IOException {
        var builder = HttpRequest.newBuilder(request.url());
        for (var header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        builder.method(request.method(), request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Fetched(response.statusCode(), response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP fetch interrupted", error);
        }
    }
}
