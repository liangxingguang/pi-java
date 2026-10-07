package com.pijava.mcp.protocol;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Capabilities the client offers during initialization ({@code types.ts:23-28}).
 *
 * @param experimental experimental capabilities
 * @param roots        roots capability
 * @param sampling     sampling capability
 * @param elicitation  elicitation capability
 */
public record ClientCapabilities(
        @Nullable Map<String, Object> experimental,
        @Nullable Roots roots,
        @Nullable Map<String, Object> sampling,
        @Nullable Map<String, Object> elicitation) {

    /** Roots capability. */
    public record Roots(@Nullable Boolean listChanged) {
    }
}
