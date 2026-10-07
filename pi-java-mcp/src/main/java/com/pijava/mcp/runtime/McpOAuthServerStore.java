package com.pijava.mcp.runtime;

import java.util.concurrent.Callable;

import com.pijava.mcp.oauth.OAuthStateStore;

/**
 * One server's slice of {@code mcp-auth.json} (pi {@code oauth.ts:132-135}).
 */
public interface McpOAuthServerStore extends OAuthStateStore {

    /**
     * Run {@code action} while no other process refreshes this server's tokens.
     *
     * <p>Many servers rotate refresh tokens, so two refreshes with the same token lose the
     * grant ({@code oauth.ts:296-301}).</p>
     *
     * @param action what to run under the lock
     * @param <T> what {@code action} returns
     * @return what {@code action} returned
     * @throws Exception whatever {@code action} threw
     */
    <T> T withRefreshLock(Callable<T> action) throws Exception;
}
