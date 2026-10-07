package com.pijava.mcp.transport.http;

import com.pijava.ai.AbortSignal;

/**
 * Executes HTTP requests for the streamable transport (the injectable
 * {@code fetch} shape).
 */
@FunctionalInterface
interface McpHttpFetch {

    /** Execute a request; cancellation via {@code signal}. */
    McpHttpResponse execute(McpHttpRequest request, AbortSignal signal) throws Exception;
}
