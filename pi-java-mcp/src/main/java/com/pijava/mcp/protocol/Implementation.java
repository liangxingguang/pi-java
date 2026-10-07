package com.pijava.mcp.protocol;

import org.jspecify.annotations.Nullable;

/**
 * Name and version of an implementation ({@code types.ts:12-16}).
 *
 * @param name    name
 * @param version version
 * @param title   optional human-readable title
 */
public record Implementation(String name, String version, @Nullable String title) {
}
