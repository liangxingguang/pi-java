package com.pijava.mcp.protocol;

import org.jspecify.annotations.Nullable;

/**
 * A filesystem root the client exposes ({@code types.ts:18-21}).
 *
 * @param uri  root URI
 * @param name optional name
 */
public record Root(String uri, @Nullable String name) {
}
