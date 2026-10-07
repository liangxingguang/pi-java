package com.pijava.mcp.config;

import org.jspecify.annotations.Nullable;

/**
 * The settings {@code /mcp} changes ({@code config.ts:159-162}); {@code null} leaves a
 * setting alone.
 *
 * @param enabled  {@code true} is the default and removes the key of a full entry in
 *                 non-override position
 * @param exposure {@code "codemode"} is the default and removes the key the same way
 */
public record McpConfigPatch(@Nullable Boolean enabled, @Nullable McpExposure exposure) {
}
