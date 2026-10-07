package com.pijava.mcp.oauth;

/**
 * Outcome of one authorization attempt (pi flow.ts:91).
 */
public enum OAuthFlowResult {

    /** Usable credentials are stored. */
    AUTHORIZED,

    /** The user was sent to the authorization page. */
    REDIRECT
}
