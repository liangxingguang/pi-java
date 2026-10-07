package com.pijava.mcp.transport.http;

import java.io.InputStream;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A received HTTP response; the body is unconsumed and the transport decides
 * how to read it based on status and content type.
 *
 * @param status      status code
 * @param headers     first value per header, case-insensitive keys
 * @param contentType Content-Type without parameters, lower-case
 * @param body        response body stream
 */
record McpHttpResponse(
        int status,
        Map<String, String> headers,
        @Nullable String contentType,
        InputStream body) {
}
