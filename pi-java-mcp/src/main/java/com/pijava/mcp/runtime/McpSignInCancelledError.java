package com.pijava.mcp.runtime;

/**
 * The user gave up on an MCP sign-in (pi {@code McpSignInCancelledError}, {@code oauth.ts:376-381}).
 */
public class McpSignInCancelledError extends RuntimeException {

    /** Create with the fixed message. */
    public McpSignInCancelledError() {
        super("Sign-in cancelled");
    }
}
