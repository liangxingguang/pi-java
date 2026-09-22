package com.pijava.ai.protocol;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
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
            request.model() == null ? null : request.modelId().provider(),
            request.model() == null ? null : request.modelId().modelName(),
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
                            : request.modelId().provider() + "/" + request.modelId().modelName();
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
            var attached = withTerminalUsage(event, event.partial().withIdentity(
                apiName(),
                request.model() == null ? null : request.modelId().provider(),
                request.model() == null ? null : request.modelId().modelName(),
                timestamp));
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
     * 终局事件（{@code StreamDone}／{@code StreamError}）的快照补计量：车道整条流
     * 没报过用量（{@code StreamPartialBuilder} 的 {@code usage} 字段仍为 null）⇒
     * 取事件自带的 {@link com.pijava.ai.stream.StreamEvent.UsageInfo}，没有就给
     * {@link #ZERO_USAGE}。中间帧**不管** —— 车道可能流到一半才报用量，那时键缺席
     * 与 pi 的「还没有」同形。
     *
     * <p><b>为什么归缝管</b>：pi 的 {@code AssistantMessage.usage} 是<b>必填</b>
     * （{@code ai/src/types.ts:439}），且 pi <b>没有任何一条路径</b>会产出没 usage 的
     * 助手消息 —— 11 个 provider 适配器、{@code lazy.ts}、中止/错误路
     * （{@code agent.ts:511-527} 的 {@code EMPTY_USAGE}、{@code recovery.ts:28-40}
     * 的 {@code ZERO_USAGE}）全都显式给零值（包⑨ B41 逐条核过，{@code docs/36}）；
     * pi 的 faux 更是每条消息都经 {@code cloneMessage} 写
     * {@code usage: cloned.usage ?? DEFAULT_USAGE}（{@code providers/faux.ts:289}）。</p>
     *
     * <p>不影响既有车道：正常报过用量的流在 {@code partial.usage() != null} 处直接返回。</p>
     *
     * <p>⚠️ <b>边界</b>：生产者<b>自己挂过身份</b>的事件（{@code partial.api() != null}，
     * 即 conformance 桩那一类）在 {@code onNext} 的早返回处走掉，<b>不经这里</b> ——
     * 那是「生产者已成形」的通道，本包不动它。</p>
     */
    private static AssistantMessage withTerminalUsage(
            StreamEvent event, AssistantMessage partial) {
        if (partial.usage() != null) {
            return partial;
        }
        return switch (event) {
            case StreamEvent.StreamDone done -> partial.withUsage(
                done.usage() != null ? done.usage() : ZERO_USAGE);
            case StreamEvent.StreamError ignored -> partial.withUsage(ZERO_USAGE);
            default -> partial;
        };
    }

    /**
     * 零用量值对象（pi 的 {@code DEFAULT_USAGE}／{@code EMPTY_USAGE} 对应物）。
     * {@code partial} 组件为 null —— 与 {@code StreamPartialBuilder} 把用量挂到快照上
     * 时的形状一致（那是「消息的 usage 字段」，不是「usage 事件的快照」）。
     */
    private static final com.pijava.ai.stream.StreamEvent.UsageInfo ZERO_USAGE =
        new com.pijava.ai.stream.StreamEvent.UsageInfo(0, 0, null);

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
        return resolveAuth(options, envVar).value();
    }

    /**
     * 解析凭证的**值 ＋ 形态**（包 A0 步7，{@code docs/43 D5}）。
     *
     * <p>形态只可能来自 {@code options}（由凭证解析层 {@code auth.Credentials} 定下）；
     * 从**环境变量**回落来的值一律是 {@link AuthKind#API_KEY} —— 那条回落路径读的是
     * {@code <PROVIDER>_API_KEY}，不是 token 变量。</p>
     *
     * @param options 请求选项（{@code apiKey} ＋ {@code authKind}）
     * @param envVar  API key 环境变量名；空/缺席 ⇒ 本地无鉴权 provider（占位 {@code "local"}）
     */
    protected static ResolvedAuth resolveAuth(ApiOptions options, String envVar) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return new ResolvedAuth(options.authKind(), options.apiKey());
        }
        if (envVar != null && !envVar.isBlank()) {
            var env = System.getenv(envVar);
            if (env != null && !env.isBlank()) {
                return new ResolvedAuth(AuthKind.API_KEY, env);
            }
        }
        if (envVar == null || envVar.isBlank()) {
            return new ResolvedAuth(AuthKind.API_KEY, "local");
        }
        throw new IllegalStateException("No API key. Set " + envVar + " or pass apiKey.");
    }

    /**
     * 解析结果：凭证值 ＋ 它在线上该走的形态。
     *
     * @param kind  凭证形态（{@code x-api-key} ／ {@code Authorization: Bearer} ／ OAuth＋身份头）
     * @param value 凭证值
     */
    protected record ResolvedAuth(AuthKind kind, String value) {}
}
