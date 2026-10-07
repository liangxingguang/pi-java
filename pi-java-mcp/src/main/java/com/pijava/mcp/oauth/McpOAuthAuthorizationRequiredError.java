package com.pijava.mcp.oauth;

/**
 * OAuth cannot proceed without a user visiting an authorization page
 * (pi errors.ts:50-55).
 */
public class McpOAuthAuthorizationRequiredError extends RuntimeException {

    /** Create with the fixed message. */
    public McpOAuthAuthorizationRequiredError() {
        super("MCP OAuth authorization requires user interaction");
    }
}
