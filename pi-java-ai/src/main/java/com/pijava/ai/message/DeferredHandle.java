package com.pijava.ai.message;

import java.util.Map;

/**
 * Provider handle for a deferred (asynchronous) response, aligned with pi
 * {@code ai/types.ts:397}.
 *
 * <p>The handle is what a provider returns instead of a final assistant
 * message when the request continues in the background. Carrying it lets the
 * final message be reconstructed later from {@code data}.</p>
 *
 * <p><b>No producer in pi-java</b> (docs/23 D2): no provider implements
 * deferral, so nothing constructs one outside tests. pi is in the same state —
 * its type exists but only the faux test provider returns one.</p>
 *
 * @param provider    provider name (e.g. {@code "anthropic"})
 * @param modelId     model id the request was made against
 * @param api         provider API discriminator (e.g. {@code "anthropic-messages"})
 * @param id          provider token: a response id or batch id plus row id
 * @param expiresAt   optional epoch-ms expiry
 * @param pollAfterMs optional suggested poll delay
 * @param data        provider conversion data needed to rebuild the message
 */
public record DeferredHandle(
    String provider,
    String modelId,
    String api,
    String id,
    Long expiresAt,
    Long pollAfterMs,
    Map<String, Object> data
) {
    /** Defensively copies {@code data} when non-null. */
    public DeferredHandle {
        data = data == null ? null : Map.copyOf(data);
    }
}
