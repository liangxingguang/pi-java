package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

/**
 * Outcome shown on the browser page after the redirect
 * (pi callback.ts:10).
 */
public sealed interface OAuthCallbackPage {

    /** The authorization completed. */
    record Ok() implements OAuthCallbackPage {
    }

    /**
     * The authorization failed.
     *
     * @param message the headline shown to the user
     * @param details the server's error description, when it sent one
     */
    record Failed(String message, @Nullable String details) implements OAuthCallbackPage {
    }

    /** The success page. */
    static OAuthCallbackPage ok() {
        return new Ok();
    }
}
