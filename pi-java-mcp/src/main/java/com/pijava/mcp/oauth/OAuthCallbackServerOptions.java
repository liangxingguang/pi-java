package com.pijava.mcp.oauth;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Options of an {@link OAuthCallbackServer} (pi callback.ts:12-27).
 *
 * @param host address to listen on; {@code 127.0.0.1} by default
 * @param redirectHost host name in the redirect URL, for a client registered
 *                     with a different name than the listening address
 * @param port listening port; {@code 0} picks a free one
 * @param path path receiving the callback; {@code /callback} by default
 * @param extraPaths more paths that receive the callback
 * @param timeoutMs how long a pending request may wait
 * @param renderPage renders the browser page as HTML instead of plain text
 */
public record OAuthCallbackServerOptions(
        @Nullable String host,
        @Nullable String redirectHost,
        int port,
        @Nullable String path,
        @Nullable List<String> extraPaths,
        @Nullable Long timeoutMs,
        @Nullable Function<OAuthCallbackPage, String> renderPage) {

    /** Default options: loopback, ephemeral port, {@code /callback}. */
    public static OAuthCallbackServerOptions defaults() {
        return new OAuthCallbackServerOptions(null, null, 0, null, null, null, null);
    }
}
