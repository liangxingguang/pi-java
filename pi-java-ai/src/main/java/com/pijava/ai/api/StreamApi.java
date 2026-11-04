package com.pijava.ai.api;

import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.StopReason;
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
     * <p>Subscribing is what starts the request. The returned publisher
     * performs no work until it is subscribed to, so a publisher that is
     * obtained and never subscribed to issues no provider request at all.
     * The producer starts once, on the first {@code subscribe}, however many
     * subscribers arrive; subscribers that attach later do not replay the
     * events they missed.</p>
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

    /**
     * Fetch the events of a deferred response (docs/20 §6.4; pi
     * {@code Api.fetchDeferred}, {@code types.ts:326-327}).
     *
     * <p>Subscribing starts the fetch, exactly like {@link #stream}. The
     * default has no producer to back it: providers that do not implement
     * deferral keep pi's "the method is absent" semantics by throwing
     * {@link UnsupportedOperationException}.</p>
     *
     * @param model   the model the deferred request was made against
     * @param handle  the handle carried by the deferred message
     * @param options fetch options (the faux provider reads {@code onResponse})
     * @return a publisher of fetch events
     */
    default Flow.Publisher<StreamEvent> fetchDeferred(
            ModelInfo model, DeferredHandle handle, ApiOptions options) {
        throw new UnsupportedOperationException("Deferred responses are not supported");
    }

    /**
     * Synchronous convenience form of {@link #fetchDeferred}.
     *
     * <p>Events are buffered into a blocking queue; a producer that completes
     * without a terminal event yields a synthetic error rather than a silent
     * success (the same liveness rule as {@code AbstractChatApi.streamBlocking}).</p>
     *
     * @param model   the model the deferred request was made against
     * @param handle  the handle carried by the deferred message
     * @param options fetch options
     * @return a closeable iterator of fetch events
     */
    default StreamIterator fetchDeferredBlocking(
            ModelInfo model, DeferredHandle handle, ApiOptions options) {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        fetchDeferred(model, handle, options).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(StreamEvent event) {
                queue.offer(event);
            }

            @Override
            public void onError(Throwable throwable) {
                queue.offer(StreamEvent.StreamError.settle(
                    StopReason.ERROR, throwable, AssistantMessage.empty()));
            }

            @Override
            public void onComplete() {
                queue.offer(StreamEvent.StreamError.settle(
                    StopReason.ERROR,
                    new IllegalStateException("stream completed without a terminal event"),
                    AssistantMessage.empty()));
            }
        });
        return new BlockingStreamIterator(queue);
    }

    /**
     * Best-effort cancellation of a deferred response (pi
     * {@code Api.cancelDeferred}, {@code types.ts:329-330}).
     *
     * <p>The default throws {@link UnsupportedOperationException}: a provider
     * that never produced a handle has nothing to cancel.</p>
     *
     * @param model   the model the deferred request was made against
     * @param handle  the handle carried by the deferred message
     * @param options cancel options (the faux provider reads {@code onResponse})
     */
    default void cancelDeferred(
            ModelInfo model, DeferredHandle handle, ApiOptions options) {
        throw new UnsupportedOperationException("Deferred responses are not supported");
    }

    /** Queue-backed iterator shared by the default blocking fetch. */
    final class BlockingStreamIterator implements StreamIterator {

        private final LinkedBlockingQueue<StreamEvent> queue;
        private boolean closed;
        private StreamEvent pending;

        BlockingStreamIterator(LinkedBlockingQueue<StreamEvent> queue) {
            this.queue = queue;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            try {
                if (pending == null) {
                    pending = queue.take();
                }
                if (pending instanceof StreamEvent.StreamDone
                        || pending instanceof StreamEvent.StreamError) {
                    closed = true;
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public StreamEvent next() {
            if (closed && pending == null) {
                throw new java.util.NoSuchElementException("stream exhausted");
            }
            if (pending != null) {
                var event = pending;
                pending = null;
                return event;
            }
            try {
                var event = queue.take();
                if (event instanceof StreamEvent.StreamDone
                        || event instanceof StreamEvent.StreamError) {
                    closed = true;
                }
                return event;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                var terminal = StreamEvent.StreamError.settle(
                    StopReason.ABORTED, e, AssistantMessage.empty());
                closed = true;
                return terminal;
            }
        }

        @Override
        public void close() {
            closed = true;
            queue.offer(StreamEvent.StreamDone.settle(
                StopReason.ABORTED, AssistantMessage.empty()));
        }
    }
}
