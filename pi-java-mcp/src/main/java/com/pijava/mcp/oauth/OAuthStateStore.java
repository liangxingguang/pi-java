package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

/**
 * Durable storage for one MCP server's OAuth state (pi provider.ts:21-24).
 */
public interface OAuthStateStore {

    /** Load the stored state, or null when nothing is stored. */
    @Nullable McpOAuthState load();

    /** Replace the stored state. */
    void save(McpOAuthState state);
}
