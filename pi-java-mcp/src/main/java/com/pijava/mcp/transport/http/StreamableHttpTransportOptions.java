package com.pijava.mcp.transport.http;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.AuthProvider;

/**
 * Options of a {@link StreamableHttpTransport}
 * (streamable-http.ts:114-123).
 *
 * @param url           endpoint
 * @param headers       static headers, copied on construction
 * @param fetch         injected fetch; JDK-backed when {@code null}
 * @param openGetStream whether to open the server-to-client stream after init
 * @param maxMessageBytes maximum event size; {@code <=0} uses the default
 * @param authProvider  credential provider
 * @param reconnect     reconnection policy; defaults apply when {@code null}
 */
record StreamableHttpTransportOptions(
        String url,
        @Nullable Map<String, String> headers,
        @Nullable McpHttpFetch fetch,
        boolean openGetStream,
        int maxMessageBytes,
        @Nullable AuthProvider authProvider,
        @Nullable ReconnectOptions reconnect) {

    /** Minimal options. */
    StreamableHttpTransportOptions(String url) {
        this(url, null, null, true, 0, null, null);
    }
}
