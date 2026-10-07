package com.pijava.mcp;

import com.pijava.ai.AbortSignal;

/**
 * Handles a JSON-RPC request the server sends (the {@code RequestHandler}
 * type at {@code client.ts:45}). A {@link java.util.concurrent.CompletionStage}
 * return value is awaited.
 */
@FunctionalInterface
interface RequestHandler {

    /** Handle the request. */
    Object handle(Object params, AbortSignal signal) throws Exception;
}
