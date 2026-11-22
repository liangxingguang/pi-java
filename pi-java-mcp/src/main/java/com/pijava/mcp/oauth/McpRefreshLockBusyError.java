package com.pijava.mcp.oauth;

/**
 * Thrown by {@code McpOAuthServerStore.withRefreshLock} when another process holds the refresh lock
 * for the whole wait: a sign-in then skips the refresh and goes to the browser step
 * (pi {@code McpRefreshLockBusyError}, {@code errors.ts:57-66}).
 */
public class McpRefreshLockBusyError extends RuntimeException {

    /** Create with the fixed message. */
    public McpRefreshLockBusyError() {
        super("Another process is refreshing the MCP server's tokens");
    }
}
