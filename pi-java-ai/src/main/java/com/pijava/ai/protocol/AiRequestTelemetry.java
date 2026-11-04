package com.pijava.ai.protocol;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.telemetry.AiTelemetrySchema;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.SpanStatus;
import com.pijava.telemetry.TelemetryCounter;
import com.pijava.telemetry.TelemetryHistogram;
import com.pijava.telemetry.TelemetryMetrics;
import com.pijava.telemetry.TelemetrySchemas;
import com.pijava.telemetry.TelemetrySpan;

/**
 * pi {@code observeModelRequest}（{@code packages/ai/src/telemetry.ts:121-205}）的
 * Java 落点。
 *
 * <p>观察点选在 {@link AbstractChatApi#stream} 的首次订阅：span 生命周期跟随生产端，
 * 不跟随消费者；只有 {@code ApiOptions.telemetry()} 非空时才创建任何东西。观察者
 * 只看事件类型、索引与长度，<b>不读正文</b>（content-free）。</p>
 */
final class AiRequestTelemetry {

    private static final int ERROR_MESSAGE_LIMIT = 500;
    private static final String PROVIDER_ERROR = "provider_error";

    private static final Map<TelemetryMetrics, Instruments> INSTRUMENTS = new WeakHashMap<>();

    private AiRequestTelemetry() {
    }

    /** pi {@code LazyStreamObserver} 的 Java 对应物。 */
    interface Observer {

        /** 观察一帧；首帧会触发 {@code pi.ai.first_chunk}。 */
        void onEvent(StreamEvent event);

        /** 观察终局消息；{@code null} ≙ pi 的 {@code undefined}（无终局结束）。 */
        void onResult(@org.jspecify.annotations.Nullable AssistantMessage result);

        /** setup／生产端异常：先补首帧，再按 {@code provider_error} 收口。 */
        void onError(Throwable error);
    }

    /**
     * 给 publisher 挂内部观察者。
     *
     * <p>前置条件：{@code options.telemetry()} 非空且请求带完整模型。缺失时静默返回，
     * 遥测绝不成为请求失败的原因。订阅发生在生产线程启动之前（调用方保证）。</p>
     */
    static void attach(SubmissionPublisher<StreamEvent> publisher, StreamRequest request,
                       ApiOptions options, String apiName) {
        if (options == null || options.telemetry() == null) {
            return;
        }
        var model = request.model();
        var id = request.modelId();
        if (model == null || id == null) {
            return;
        }
        var api = model.api() != null ? model.api() : apiName;
        var observer = observe(id.provider(), api, id.modelName(), request.reasoning(),
            options.telemetry(), false, System::nanoTime);
        wire(publisher, observer);
    }

    /**
     * docs/20 §6.6：给 deferred 取回挂 {@code pi.ai.poll} 观察者（关闭 D203）。
     *
     * <p>与 {@link #attach} 同形，但 {@code poll=true}、推理级别为空 —— pi 的
     * {@code DeferredFetchOptions} 没有 {@code reasoning} 字段。</p>
     */
    static void attachPoll(SubmissionPublisher<StreamEvent> publisher,
                           com.pijava.ai.catalog.ModelInfo model,
                           ApiOptions options, String apiName) {
        if (options == null || options.telemetry() == null || model == null
                || model.id() == null) {
            return;
        }
        var api = model.api() != null ? model.api() : apiName;
        var observer = observe(model.id().provider(), api, model.id().modelName(),
            Optional.empty(), options.telemetry(), true, System::nanoTime);
        wire(publisher, observer);
    }

    /** 观察者的订阅接线；跟随生产端终止，不跟随单个消费者。 */
    private static void wire(SubmissionPublisher<StreamEvent> publisher, Observer observer) {
        var ended = new AtomicBoolean();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(StreamEvent event) {
                observer.onEvent(event);
                var terminal = terminalPartial(event);
                if (terminal != null && ended.compareAndSet(false, true)) {
                    observer.onResult(terminal);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (ended.compareAndSet(false, true)) {
                    observer.onError(throwable);
                }
            }

            @Override
            public void onComplete() {
                if (ended.compareAndSet(false, true)) {
                    observer.onResult(null);
                }
            }
        });
    }

    /** 创建观察者；{@code poll=true} 走 {@code pi.ai.poll}。测试可直接驱动。 */
    static Observer observe(String provider, String api, String model,
                            Optional<ThinkingLevel> reasoning, TelemetrySpan parent,
                            boolean poll, LongSupplier nanoClock) {
        long startNanos = nanoClock.getAsLong();
        var metricAttributes = Map.<String, Object>of("provider", provider, "model", model);
        var startAttributes = new LinkedHashMap<String, Object>(metricAttributes);
        startAttributes.put("api", api);
        reasoning.ifPresent(level -> startAttributes.put("reasoning", level.label()));

        var instruments = instruments(parent.metrics());
        var span = parent.openSpan(new SpanOptions(
            poll ? "pi.ai.poll" : "pi.ai.request", startAttributes));
        return new ObserverImpl(span, instruments, metricAttributes, startNanos, poll, nanoClock);
    }

    private static Instruments instruments(TelemetryMetrics metrics) {
        synchronized (INSTRUMENTS) {
            return INSTRUMENTS.computeIfAbsent(metrics, AiRequestTelemetry::createInstruments);
        }
    }

    private static Instruments createInstruments(TelemetryMetrics metrics) {
        var typed = TelemetrySchemas.createTypedMetrics(
            metrics, AiTelemetrySchema.AI_TELEMETRY_SCHEMA);
        return new Instruments(
            typed.histogram("pi.ai.request.duration"),
            typed.histogram("pi.ai.first_chunk.duration"),
            typed.counter("pi.ai.tokens"),
            typed.counter("pi.ai.cost"));
    }

    /** pi 的 {@code createInstruments} 返回值：四件已绑定元数据的乐器。 */
    private record Instruments(TelemetryHistogram requestDuration,
                               TelemetryHistogram firstChunkDuration,
                               TelemetryCounter tokens,
                               TelemetryCounter cost) { }

    private static final class ObserverImpl implements Observer {

        private final TelemetrySpan span;
        private final Instruments instruments;
        private final Map<String, Object> metricAttributes;
        private final long startNanos;
        private final boolean poll;
        private final LongSupplier nanoClock;
        private final Map<Integer, Integer> toolChars = new HashMap<>();

        private boolean first = true;
        private boolean ended;

        ObserverImpl(TelemetrySpan span, Instruments instruments,
                     Map<String, Object> metricAttributes, long startNanos,
                     boolean poll, LongSupplier nanoClock) {
            this.span = span;
            this.instruments = instruments;
            this.metricAttributes = metricAttributes;
            this.startNanos = startNanos;
            this.poll = poll;
            this.nanoClock = nanoClock;
        }

        @Override
        public void onEvent(StreamEvent event) {
            firstChunk();
            if (event instanceof StreamEvent.ToolCallDelta delta) {
                toolChars.merge(delta.contentIndex(), length(delta.jsonDelta()), Integer::sum);
            } else if (event instanceof StreamEvent.TextEnd end) {
                block("text", end.contentIndex(), length(end.text()), null);
            } else if (event instanceof StreamEvent.ThinkingEnd end) {
                block("thinking", end.contentIndex(), length(end.thinking()), null);
            } else if (event instanceof StreamEvent.ToolCallEnd end) {
                int chars = toolChars.getOrDefault(end.contentIndex(), 0);
                toolChars.remove(end.contentIndex());
                block("tool_call", end.contentIndex(), chars, end.name());
            }
        }

        @Override
        public void onResult(@org.jspecify.annotations.Nullable AssistantMessage result) {
            if (ended) {
                return;
            }
            ended = true;
            try {
                if (result != null) {
                    finish(result);
                }
            } finally {
                span.close();
            }
        }

        @Override
        public void onError(Throwable error) {
            if (ended) {
                return;
            }
            firstChunk();
            onResult(AssistantMessage.empty()
                .withStopReason(StopReason.ERROR)
                .withErrorMessage(textOf(error)));
        }

        private void finish(AssistantMessage message) {
            var usage = message.usage() == null ? Usage.of(0, 0) : message.usage().toUsage();
            var cost = usage.cost() == null ? Usage.Cost.zero() : usage.cost();
            var stop = message.stopReason();

            var end = new LinkedHashMap<String, Object>();
            if (stop != null) {
                end.put("stop", stop.wireName());
            }
            end.put("usage.input", usage.input());
            end.put("usage.output", usage.output());
            end.put("usage.cache_read", usage.cacheRead());
            end.put("usage.cache_write", usage.cacheWrite());
            end.put("cost.total", cost.total());
            if (message.responseId() != null) {
                end.put("response.id", message.responseId());
            }
            span.setAttributes(end);

            if (stop == StopReason.ERROR) {
                span.setStatus(new SpanStatus.Error(
                    PROVIDER_ERROR, bounded(message.errorMessage())));
            }

            var durationAttributes = new LinkedHashMap<String, Object>(metricAttributes);
            if (stop != null) {
                durationAttributes.put("stop", stop.wireName());
            }
            instruments.requestDuration().record(elapsedMs(), durationAttributes, span);

            if (!poll || (stop != StopReason.PENDING && stop != StopReason.DEFERRED)) {
                instruments.tokens().add(usage.input(), withKind("input"));
                instruments.tokens().add(usage.output(), withKind("output"));
                instruments.tokens().add(usage.cacheRead(), withKind("cache_read"));
                instruments.tokens().add(usage.cacheWrite(), withKind("cache_write"));
                instruments.cost().add(cost.total(), metricAttributes);
            }
        }

        private void firstChunk() {
            if (!first) {
                return;
            }
            first = false;
            span.addEvent("pi.ai.first_chunk", Map.of());
            instruments.firstChunkDuration().record(elapsedMs(), metricAttributes, span);
        }

        private void block(String type, int index, int chars,
                           @org.jspecify.annotations.Nullable String tool) {
            var attributes = new LinkedHashMap<String, Object>();
            attributes.put("type", type);
            attributes.put("index", index);
            attributes.put("chars", chars);
            if (tool != null) {
                attributes.put("tool", tool);
            }
            span.addEvent("pi.ai.block", attributes);
        }

        private Map<String, Object> withKind(String kind) {
            var attributes = new LinkedHashMap<String, Object>(metricAttributes);
            attributes.put("kind", kind);
            return attributes;
        }

        private double elapsedMs() {
            return (nanoClock.getAsLong() - startNanos) / 1_000_000.0;
        }
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static String bounded(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= ERROR_MESSAGE_LIMIT
            ? message : message.substring(0, ERROR_MESSAGE_LIMIT);
    }

    private static String textOf(Throwable error) {
        if (error == null) {
            return "";
        }
        var message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    private static AssistantMessage terminalPartial(StreamEvent event) {
        if (event instanceof StreamEvent.StreamDone done) {
            var partial = done.partial();
            if (partial == null) {
                partial = AssistantMessage.empty().withStopReason(done.reason());
            } else if (partial.stopReason() == null) {
                partial = partial.withStopReason(done.reason());
            }
            // docs/13 §4.5：终局事件可能只把 usage 放在事件上（ConformanceFixtures 的形状）；
            // 观察者订阅的是原始 publisher，看不到下游 IdentitySubscriber 的零值兜底。
            if (partial.usage() == null && done.usage() != null) {
                partial = partial.withUsage(done.usage());
            }
            return partial;
        }
        if (event instanceof StreamEvent.StreamError error) {
            var partial = error.partial();
            if (partial == null) {
                partial = AssistantMessage.empty().withStopReason(error.reason());
            } else if (partial.stopReason() == null) {
                partial = partial.withStopReason(error.reason());
            }
            // docs/13 §4.5：正文优先取消息；缺失时走全仓唯一错误文本读取入口（B114）。
            if (partial.errorMessage() == null) {
                var text = StreamEvent.StreamError.textOf(error);
                if (text != null) {
                    partial = partial.withErrorMessage(text);
                }
            }
            return partial;
        }
        return null;
    }
}
