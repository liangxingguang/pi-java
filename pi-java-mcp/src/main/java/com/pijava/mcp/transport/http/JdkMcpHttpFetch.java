package com.pijava.mcp.transport.http;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.pijava.ai.AbortSignal;

/**
 * JDK {@link java.net.http.HttpClient}-backed {@link McpHttpFetch}.
 */
final class JdkMcpHttpFetch implements McpHttpFetch {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public McpHttpResponse execute(McpHttpRequest request, AbortSignal signal) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(request.url()));
        request.headers().forEach(builder::header);
        var publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        builder.method(request.method(), publisher);

        var future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        var unsubscribe = signal.onAbort(() -> future.cancel(true));
        HttpResponse<InputStream> response;
        try {
            response = future.get();
        } finally {
            unsubscribe.run();
        }

        var headers = collectHeaders(response);
        var contentType = parseContentType(headers);
        var caseInsensitive = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        return new McpHttpResponse(response.statusCode(), caseInsensitive,
                contentType, response.body());
    }

    private static Map<String, String> collectHeaders(HttpResponse<?> response) {
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

    private static String parseContentType(Map<String, String> headers) {
        var raw = headers.get("Content-Type");
        if (raw == null) {
            return null;
        }
        var semicolon = raw.indexOf(';');
        return (semicolon < 0 ? raw : raw.substring(0, semicolon)).trim().toLowerCase();
    }
}
