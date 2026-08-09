package com.pijava.ai.api;

/**
 * Combined interface that provides both streaming and non-streaming chat.
 *
 * <p>Most provider implementations will implement this interface
 * directly rather than implementing {@link StreamApi} and
 * {@link SimpleApi} separately.</p>
 */
public interface ChatApi extends StreamApi, SimpleApi {
}
