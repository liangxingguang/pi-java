package com.pijava.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Buffered HTTP fetch used by OAuth discovery, the OAuth flow, and the auth
 * provider's challenge handling (pi's {@code McpFetch}, auth-provider.ts:1).
 *
 * <p>The transport keeps its own streaming fetch
 * ({@code McpHttpFetch}); it bridges to this shape when handing a fetch to an
 * {@link AuthProvider}.</p>
 */
@FunctionalInterface
public interface McpFetch {

    /** Fetch one resource. Network failures surface as {@link IOException}. */
    Fetched fetch(Request request) throws IOException;

    /**
     * One outgoing request.
     *
     * @param method HTTP method
     * @param url target URL
     * @param headers header lines
     * @param body optional body bytes
     */
    record Request(String method, URI url, Map<String, String> headers, byte @Nullable [] body) {

        /** A GET with the given headers. */
        public static Request get(URI url, Map<String, String> headers) {
            return new Request("GET", url, headers, null);
        }
    }

    /**
     * A buffered response.
     *
     * @param status HTTP status code
     * @param body response body bytes
     */
    record Fetched(int status, byte[] body) {

        /** Whether the status is 2xx. */
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
