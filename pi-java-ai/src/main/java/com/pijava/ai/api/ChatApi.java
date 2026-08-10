package com.pijava.ai.api;

/**
 * Combined interface that provides both streaming and non-streaming chat.
 *
 * <p>Most provider implementations will implement this interface
 * directly rather than implementing {@link StreamApi} and
 * {@link SimpleApi} separately.</p>
 *
 * <p>Extends {@link ProviderApi} so it can serve as the sole permit
 * of the sealed ProviderApi hierarchy in Phase 1.</p>
 */
public non-sealed interface ChatApi extends ProviderApi, StreamApi, SimpleApi {
}
