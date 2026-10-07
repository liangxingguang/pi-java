package com.pijava.mcp.protocol;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Capabilities a server reports during initialization ({@code types.ts:30-37}).
 *
 * @param experimental experimental capabilities
 * @param logging      logging capability
 * @param prompts      prompts capability
 * @param resources    resources capability
 * @param tools        tools capability
 * @param completions  completions capability
 */
public record ServerCapabilities(
        @Nullable Map<String, Object> experimental,
        @Nullable Map<String, Object> logging,
        @Nullable Prompts prompts,
        @Nullable Resources resources,
        @Nullable Tools tools,
        @Nullable Map<String, Object> completions) {

    /** Prompts capability. */
    public record Prompts(@Nullable Boolean listChanged) {
    }

    /** Resources capability. */
    public record Resources(@Nullable Boolean subscribe, @Nullable Boolean listChanged) {
    }

    /** Tools capability. */
    public record Tools(@Nullable Boolean listChanged) {
    }
}
