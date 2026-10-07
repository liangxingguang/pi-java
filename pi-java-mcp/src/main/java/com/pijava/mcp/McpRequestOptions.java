package com.pijava.mcp;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.protocol.ProgressNotification;

/**
 * Per-request options ({@code client.ts:54-58}).
 *
 * @param signal    cancellation signal
 * @param timeoutMs timeout in ms; {@code <=0} uses the client default
 * @param onProgress progress callback
 */
public record McpRequestOptions(
        @Nullable AbortSignal signal,
        long timeoutMs,
        @Nullable Consumer<ProgressNotification> onProgress) {

    /** Empty options. */
    public static McpRequestOptions none() {
        return new McpRequestOptions(null, 0, null);
    }
}
