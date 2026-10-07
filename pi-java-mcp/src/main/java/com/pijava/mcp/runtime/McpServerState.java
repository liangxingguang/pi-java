package com.pijava.mcp.runtime;

/**
 * Where one configured server stands (pi {@code runtime.ts:56}).
 *
 * <p>{@code DISCONNECTED} means the connection dropped — for example the stdio server exited;
 * the next call reconnects.</p>
 */
public enum McpServerState {

    /** A connection attempt is running. */
    CONNECTING,

    /** Connected and initialized. */
    CONNECTED,

    /** The connection dropped; the next call reconnects. */
    DISCONNECTED,

    /** The server rejected the credentials; the user has to sign in. */
    NEEDS_AUTH,

    /** The last connection attempt failed. */
    FAILED,

    /** The connection was closed and will not be reopened. */
    CLOSED
}
