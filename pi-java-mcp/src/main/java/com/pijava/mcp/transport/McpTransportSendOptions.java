package com.pijava.mcp.transport;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;

/**
 * Options of one send ({@code transport.ts:9-18}).
 *
 * @param signal aborted once the sender no longer waits for a reply (the
 *               request was cancelled, timed out, answered, or the client
 *               closed). A transport must not dispatch the message after that,
 *               for example when retrying after an authorization refresh. It
 *               does not undo a message already dispatched.
 */
public record McpTransportSendOptions(@Nullable AbortSignal signal) {
}
