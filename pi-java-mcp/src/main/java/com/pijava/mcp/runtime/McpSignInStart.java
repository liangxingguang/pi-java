package com.pijava.mcp.runtime;

/**
 * Result of the first half of a sign-in (pi {@code McpSignInStart}, {@code sign-in.ts:53-54}): the
 * stored refresh token signed the server in, or the browser step is next.
 */
public sealed interface McpSignInStart permits McpSignInStart.Authorized, McpSignInRedirect {

    /** The stored tokens were refreshed: there is nothing left to do. */
    record Authorized() implements McpSignInStart {
    }
}
