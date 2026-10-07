package com.pijava.mcp.runtime;

import java.net.URI;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;

/**
 * How a sign-in talks to the user (pi {@code McpSignInPrompt}, {@code oauth.ts:365-374}).
 */
public interface McpSignInPrompt {

    /**
     * Show the authorization URL to the user and open it in a browser.
     *
     * @param url the authorization URL
     */
    void showAuthorizationUrl(URI url);

    /**
     * Ask for the redirect URL from the browser address bar, for when the browser cannot reach
     * the loopback callback (for example over SSH).
     *
     * <p>Aborted once the callback arrives. Resolves to {@code null} or an empty string when the
     * user cancels.</p>
     *
     * @param signal aborted once the answer is no longer needed
     * @return the pasted URL, or {@code null}
     * @throws Exception when asking failed
     */
    @Nullable String promptForRedirectUrl(AbortSignal signal) throws Exception;
}
