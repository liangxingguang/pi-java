package com.pijava.mcp.protocol;

import org.jspecify.annotations.Nullable;

/**
 * Hints about what a tool does ({@code types.ts:64-70}).
 *
 * @param title          optional title
 * @param readOnlyHint   the tool does not modify its environment
 * @param destructiveHint the tool may delete or overwrite data
 * @param idempotentHint repeating a call has no further effect
 * @param openWorldHint  the tool reaches an open world of external entities
 */
public record ToolAnnotations(
        @Nullable String title,
        @Nullable Boolean readOnlyHint,
        @Nullable Boolean destructiveHint,
        @Nullable Boolean idempotentHint,
        @Nullable Boolean openWorldHint) {
}
