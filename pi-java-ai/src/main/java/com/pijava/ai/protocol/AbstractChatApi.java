package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

/**
 * Shared base for protocol adapters.
 *
 * <p>Provides default implementations of {@code stream()}, {@code streamBlocking()},
 * and {@code send()} so that concrete adapters only need to implement
 * {@link #streamInternal(StreamRequest, SubmissionPublisher)} — the
 * provider-specific streaming logic.</p>
 */
public abstract class AbstractChatApi implements ChatApi {

    @Override
    public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options) {
        var publisher = new SubmissionPublisher<StreamEvent>();
        Thread.startVirtualThread(() -> {
            try {
                streamInternal(request, publisher);
                publisher.close();
            } catch (Exception e) {
                publisher.closeExceptionally(e);
            }
        });
        return publisher;
    }

    @Override
    public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        stream(request, options).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription s) {
                this.subscription = s; s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent e) { queue.offer(e); }
            @Override public void onError(Throwable t) {
                queue.offer(new StreamEvent.StreamError("error", t, AssistantMessage.empty()));
            }
            @Override public void onComplete() {
                // Safety net: the SubmissionPublisher can drop the adapter's
                // final StreamDone (submit() immediately followed by close()),
                // which would leave QueueStreamIterator.hasNext() blocking
                // forever on an empty queue. Emit a synthetic done so the
                // iterator always terminates.
                queue.offer(new StreamEvent.StreamDone(
                    "stop", null, AssistantMessage.empty()));
            }
        });
        return new QueueStreamIterator(queue);
    }

    @Override
    public Message send(StreamRequest request, ApiOptions options) {
        var blocks = new ArrayList<ContentBlock>();
        try (var iter = streamBlocking(request, options)) {
            while (iter.hasNext()) {
                var event = iter.next();
                if (event instanceof StreamEvent.StreamDone done) {
                    return new Message.AssistantMessage(done.partial().content());
                }
                if (event instanceof StreamEvent.StreamError) {
                    break;
                }
            }
        } catch (Exception e) {
            throw new com.pijava.ai.http.PiHttpException(0, "Streaming failed", e);
        }
        return new Message.AssistantMessage(blocks);
    }

    /**
     * Provider-specific streaming logic.
     * Implementations should submit {@link StreamEvent}s to the publisher,
     * ending with {@link com.pijava.ai.stream.StreamPartialBuilder#emitDone}
     * or {@link com.pijava.ai.stream.StreamPartialBuilder#emitError}.
     */
    protected abstract void streamInternal(StreamRequest request,
                                           SubmissionPublisher<StreamEvent> publisher);

    /** 解析 API key：优先 options.apiKey，否则回落环境变量。 */
    protected static String resolveApiKey(ApiOptions options, String envVar) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        var env = System.getenv(envVar);
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException("No API key. Set " + envVar + " or pass apiKey.");
    }
}
