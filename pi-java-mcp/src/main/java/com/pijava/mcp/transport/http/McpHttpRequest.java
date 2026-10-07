package com.pijava.mcp.transport.http;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One outgoing HTTP request.
 *
 * @param method HTTP method
 * @param url    target URL
 * @param headers header lines
 * @param body   optional body bytes
 */
record McpHttpRequest(
        String method,
        String url,
        Map<String, String> headers,
        byte @Nullable [] body) {

    /** Request without a body. */
    McpHttpRequest(String method, String url, Map<String, String> headers) {
        this(method, url, new LinkedHashMap<>(headers), null);
    }
}
