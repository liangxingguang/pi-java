package com.pijava.ai.api;

import java.util.concurrent.Flow;

import com.pijava.ai.stream.StreamEvent;

/**
 * Streaming chat API.
 *
 * <p>Implementations send a {@link StreamRequest} to an LLM provider
 * and produce a reactive stream of {@link StreamEvent} values. Two
 * consumption styles are supported:</p>
 *
 * <ul>
 *   <li>{@link #stream(StreamRequest, ApiOptions)} — JDK {@link Flow.Publisher}</li>
 *   <li>{@link #streamBlocking(StreamRequest, ApiOptions)} — virtual-thread-friendly
 *       synchronous iterator (recommended for agent loops)</li>
 * </ul>
 */
public interface StreamApi {

    /**
     * Stream a chat request as a reactive publisher.
     *
     * @param request the chat request
     * @param options API call options
     * @return a publisher of stream events
     */
    Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options);

    /**
     * Stream a chat request and return a synchronous, closeable iterator.
     *
     * <p>This is the recommended style for agent loops: the iterator
     * blocks the virtual thread on each {@code next()} call, making
     * control flow straightforward.</p>
     *
     * @param request the chat request
     * @param options API call options
     * @return a closeable iterable of stream events
     */
    StreamIterator streamBlocking(StreamRequest request, ApiOptions options);
}
