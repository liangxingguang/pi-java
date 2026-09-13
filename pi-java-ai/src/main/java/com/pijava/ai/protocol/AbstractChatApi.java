package com.pijava.ai.protocol;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared base for protocol adapters.
 *
 * <p>Provides default implementations of {@code stream()}, {@code streamBlocking()},
 * and {@code send()} so that concrete adapters only need to implement
 * {@link #streamInternal(StreamRequest, SubmissionPublisher)} — the
 * provider-specific streaming logic.</p>
 *
 * <p><b>3a 身份挂载点</b>：pi 在 provider 构造消息时就写死
 * {@code api}/{@code provider}/{@code model}/{@code timestamp}，流的每个 partial
 * 都携带它们（{@code assistant-message-frame.ts:77-92}）。pi-java 的 partial 由
 * 各 adapter 的 builder 生成、不带模型上下文，故在事件出口统一改写：
 * {@link #stream} 返回的 publisher 把每个事件的 partial 换成挂好身份的副本，
 * 一次流固定一个 timestamp。{@link #send} 也据此产出全字段终局消息。</p>
 */
public abstract class AbstractChatApi implements ChatApi {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractChatApi.class);

    /** pi 协议判别字面量（{@code packages/ai/src/types.ts:18-28} 的 KnownApi）。 */
    public abstract String apiName();

    /** 挂载身份后的空快照基底（provider/model 来自请求；request 无模型 ⇒ 键省略）。 */
    private AssistantMessage identityBase(StreamRequest request, Instant timestamp) {
        var base = AssistantMessage.empty().withIdentity(
            apiName(),
            request.model() == null ? null : request.model().provider(),
            request.model() == null ? null : request.model().modelName(),
            timestamp);
        return base;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The returned publisher attaches each subscriber <em>before</em> the
     * producer can publish anything, and starts the producer virtual thread
     * lazily on the first {@code subscribe} — at most once, however many
     * subscribers arrive.</p>
     *
     * <p>Ordering matters: {@link SubmissionPublisher#submit} silently discards
     * an item when no subscriber is attached yet. A producer that wins the race
     * to its first {@code submit} (which every adapter does, emitting
     * {@code Start} before any network I/O) would therefore lose that event
     * permanently. Attaching first closes the window instead of narrowing it.</p>
     */
    @Override
    public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options) {
        var publisher = new SubmissionPublisher<StreamEvent>();
        var started = new AtomicBoolean();
        // 身份挂载只发生一次/每次订阅一致：一个流一个 timestamp。
        var timestamp = Instant.now();
        return subscriber -> {
            publisher.subscribe(new IdentitySubscriber(subscriber, request, timestamp));
            if (started.compareAndSet(false, true)) {
                Thread.startVirtualThread(() -> {
                    try {
                        streamInternal(request, publisher);
                        publisher.close();
                    } catch (Exception e) {
                        // Best-effort logging must never break error delivery.
                        var model = request.model() == null ? "unknown"
                            : request.model().provider() + "/" + request.model().modelName();
                        LOG.warn("[ai] LLM stream failed for model {}", model, e);
                        publisher.closeExceptionally(e);
                    }
                });
            }
        };
    }

    @Override
    public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        var netTimestamp = Instant.now();
        stream(request, options).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription s) {
                this.subscription = s; s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent e) { queue.offer(e); }
            @Override public void onError(Throwable t) {
                queue.offer(new StreamEvent.StreamError("error", t,
                    identityBase(request, netTimestamp)));
            }
            @Override public void onComplete() {
                // Safety net: the SubmissionPublisher can drop the adapter's
                // final StreamDone (submit() immediately followed by close()),
                // which would leave QueueStreamIterator.hasNext() blocking
                // forever on an empty queue. Emit a synthetic done so the
                // iterator always terminates.
                queue.offer(new StreamEvent.StreamDone(
                    "stop", null, identityBase(request, netTimestamp)));
            }
        });
        return new QueueStreamIterator(queue);
    }

    @Override
    public Message send(StreamRequest request, ApiOptions options) {
        var blocks = new ArrayList<ContentBlock>();
        var timestamp = Instant.now();
        try (var iter = streamBlocking(request, options)) {
            while (iter.hasNext()) {
                var event = iter.next();
                if (event instanceof StreamEvent.StreamDone done) {
                    // 全字段投影（3a 前只搬 content，usage/身份全丢）。
                    return Message.AssistantMessage.fromPartial(done.partial());
                }
                if (event instanceof StreamEvent.StreamError) {
                    break;
                }
            }
        } catch (Exception e) {
            throw new com.pijava.ai.http.PiHttpException(0, "Streaming failed", e);
        }
        return Message.AssistantMessage.fromPartial(
            identityBase(request, timestamp).withContent(blocks));
    }

    /**
     * 转发订阅者：{@code onNext} 前把事件的 partial 换成挂好
     * api/provider/model/timestamp 的副本，订阅协议原样透传
     * （request/cancel 直接落到上游 subscription）。
     */
    private final class IdentitySubscriber implements Flow.Subscriber<StreamEvent> {

        private final Flow.Subscriber<? super StreamEvent> downstream;
        private final StreamRequest request;
        private final Instant timestamp;

        IdentitySubscriber(Flow.Subscriber<? super StreamEvent> downstream,
                           StreamRequest request, Instant timestamp) {
            this.downstream = downstream;
            this.request = request;
            this.timestamp = timestamp;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            downstream.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { subscription.request(n); }
                @Override public void cancel() { subscription.cancel(); }
            });
        }

        @Override
        public void onNext(StreamEvent event) {
            if (event.partial() == null || event.partial().api() != null) {
                downstream.onNext(event);
                return;
            }
            var attached = event.partial().withIdentity(
                apiName(),
                request.model() == null ? null : request.model().provider(),
                request.model() == null ? null : request.model().modelName(),
                timestamp);
            downstream.onNext(StreamEvent.withPartial(event, attached));
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }

    /**
     * Provider-specific streaming logic.
     * Implementations should submit {@link StreamEvent}s to the publisher,
     * ending with {@link com.pijava.ai.stream.StreamPartialBuilder#emitDone}
     * or {@link com.pijava.ai.stream.StreamPartialBuilder#emitError}.
     */
    protected abstract void streamInternal(StreamRequest request,
                                           SubmissionPublisher<StreamEvent> publisher);

    /**
     * 解析 API key：优先 options.apiKey，否则回落环境变量。
     *
     * <p>{@code apiKeyEnvVar} 为空表示本地无鉴权 Provider（如 Ollama），此时返回
     * 占位 key（P6-1 显式子任务），不抛异常。</p>
     */
    protected static String resolveApiKey(ApiOptions options, String envVar) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        if (envVar != null && !envVar.isBlank()) {
            var env = System.getenv(envVar);
            if (env != null && !env.isBlank()) {
                return env;
            }
        }
        if (envVar == null || envVar.isBlank()) {
            return "local";
        }
        throw new IllegalStateException("No API key. Set " + envVar + " or pass apiKey.");
    }
}
