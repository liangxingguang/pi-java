package com.pijava.mcp.runtime;

import com.pijava.mcp.AuthProvider;

/**
 * The transport-facing auth provider of an MCP server, with the extra hook the connection
 * needs at shutdown (pi {@code oauth.ts:284-287}).
 */
public interface McpAuthProvider extends AuthProvider {

    /**
     * Resolve when no refresh is running, so shutdown does not drop rotated tokens before they
     * are saved.
     *
     * @throws Exception when an in-flight refresh failed
     */
    void settled() throws Exception;
}
