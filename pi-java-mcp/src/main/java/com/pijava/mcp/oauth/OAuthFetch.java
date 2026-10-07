package com.pijava.mcp.oauth;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Minimal fetch used by OAuth discovery: GET a URL with headers.
 * Package 7 supplies the JDK HTTP implementation; tests script it.
 */
@FunctionalInterface
public interface OAuthFetch {

    /** Fetch one resource. Network failures surface as {@link IOException}. */
    Fetched fetch(URI url, Map<String, String> headers) throws IOException;

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
