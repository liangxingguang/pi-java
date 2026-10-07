package com.pijava.mcp.protocol;

import org.jspecify.annotations.Nullable;

/**
 * Progress notification params ({@code types.ts:52-57}).
 *
 * @param progressToken token of the request the progress belongs to
 * @param progress      current progress
 * @param total         optional total
 * @param message       optional message
 */
public record ProgressNotification(Object progressToken, double progress,
                                   @Nullable Double total, @Nullable String message) {
}
