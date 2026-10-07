package com.pijava.mcp.runtime;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.pijava.mcp.McpFetch;

/**
 * Bounds one fetch of an OAuth refresh, so it cannot hold the refresh lock or delay shutdown
 * for long (pi {@code AbortSignal.timeout(15_000)}, {@code oauth.ts:330-331}).
 *
 * <p>pi attaches the deadline to the request's {@code signal}. {@link McpFetch} has no signal,
 * so the call runs on its own thread and the caller waits with a deadline instead; a fetch that
 * outlives it finishes into nothing.</p>
 */
final class TimedMcpFetch implements McpFetch {

    private final McpFetch delegate;
    private final long timeoutMs;

    TimedMcpFetch(McpFetch delegate, long timeoutMs) {
        this.delegate = delegate;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Fetched fetch(Request request) throws IOException {
        var future = new CompletableFuture<Fetched>();
        Thread.startVirtualThread(() -> {
            try {
                future.complete(delegate.fetch(request));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            throw new IOException("MCP OAuth request timed out after " + timeoutMs + " ms", expired);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the OAuth request", interrupted);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(failure.getCause());
        }
    }
}
