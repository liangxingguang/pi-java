package com.pijava.mcp.oauth;

import org.jspecify.annotations.Nullable;

import com.pijava.mcp.McpJson;

/**
 * In-memory store that copies on every boundary, standing in for pi's
 * {@code structuredClone} (pi provider.ts:40-50).
 */
public final class MemoryOAuthStateStore implements OAuthStateStore {

    private @Nullable McpOAuthState value;

    @Override
    public @Nullable McpOAuthState load() {
        return copy(value);
    }

    @Override
    public void save(McpOAuthState state) {
        this.value = copy(state);
    }

    private static @Nullable McpOAuthState copy(@Nullable McpOAuthState state) {
        if (state == null) {
            return null;
        }
        return McpJson.mapper().convertValue(state, McpOAuthState.class);
    }
}
