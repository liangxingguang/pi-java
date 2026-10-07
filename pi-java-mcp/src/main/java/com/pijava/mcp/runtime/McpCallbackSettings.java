package com.pijava.mcp.runtime;

import java.net.URI;

import org.jspecify.annotations.Nullable;

/**
 * Where the loopback callback server listens and the redirect URI it serves
 * (pi {@code callbackSettings}, {@code oauth.ts:72-103}).
 *
 * @param host address to listen on
 * @param redirectHost host name written into the redirect URI
 * @param port listening port, or {@code null} for a free one
 * @param path path receiving the callback
 * @param fixedRedirectUrl the exact redirect URI, when the port is known
 */
record McpCallbackSettings(
        String host,
        String redirectHost,
        @Nullable Integer port,
        String path,
        @Nullable String fixedRedirectUrl) {

    /** The address pi listens on when the configured host is {@code localhost} ({@code oauth.ts:39}). */
    static final String CALLBACK_HOST = "127.0.0.1";

    /** The path the callback server serves ({@code oauth.ts:40}). */
    static final String CALLBACK_PATH = "/callback";

    /** The redirect URI used when nothing else is known ({@code oauth.ts:44}). */
    static final String FALLBACK_REDIRECT_URL = "http://" + CALLBACK_HOST + CALLBACK_PATH;

    /**
     * Derive the callback settings from a server's OAuth configuration
     * ({@code oauth.ts:84-103}).
     *
     * @param settings the OAuth settings
     * @return where to listen and what redirect URI to send
     */
    static McpCallbackSettings from(McpOAuthSettings settings) {
        var configured = settings.callbackUrl();
        var url = URI.create(configured != null ? configured : FALLBACK_REDIRECT_URL);
        var address = WebUrls.hostname(url);
        var explicitPort = WebUrls.port(url);
        var port = explicitPort != null ? explicitPort : settings.callbackPort();
        // A configured URI with a port is sent exactly as written, since servers compare it as a string.
        String fixed = null;
        if (explicitPort != null) {
            fixed = configured;
        } else if (port != null) {
            fixed = WebUrls.withPort(url, port);
        }
        return new McpCallbackSettings(
                // `localhost` is served on 127.0.0.1; browsers fall back to it when ::1 refuses.
                "localhost".equals(address) ? CALLBACK_HOST : address,
                address,
                port,
                WebUrls.pathname(url),
                fixed);
    }
}
