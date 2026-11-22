package com.pijava.mcp.runtime;

import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.oauth.McpOAuthState;

/** An in-memory server store whose refresh lock never contends. */
final class MemoryServerStore implements McpOAuthServerStore {

    private @Nullable McpOAuthState value;

    @Override
    public @Nullable McpOAuthState load() {
        return value;
    }

    @Override
    public void save(McpOAuthState state) {
        this.value = state;
    }

    @Override
    public <T> T withRefreshLock(Callable<T> action) throws Exception {
        return action.call();
    }
}
