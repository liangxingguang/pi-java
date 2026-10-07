package com.pijava.mcp.protocol;

import org.jspecify.annotations.Nullable;

/**
 * Cancelled notification params ({@code types.ts:59-62}).
 *
 * @param requestId id of the cancelled request
 * @param reason    optional reason
 */
public record CancelledNotification(Object requestId, @Nullable String reason) {
}
