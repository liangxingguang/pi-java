package com.pijava.ai.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.telemetry.HistogramOptions;
import com.pijava.telemetry.InMemoryTelemetryContext;
import com.pijava.telemetry.InstrumentOptions;
import com.pijava.telemetry.NoopTelemetryMetrics;
import com.pijava.telemetry.RecordedTelemetrySpan;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.SpanStatus;
import com.pijava.telemetry.TelemetryCounter;
import com.pijava.telemetry.TelemetryHistogram;
import com.pijava.telemetry.TelemetryMetrics;
import com.pijava.telemetry.TelemetrySpan;
import com.pijava.telemetry.TelemetryUpDownCounter;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/13 的共享测试夹具：模型、事件、适配器与遥测断言面。 */
final class AiTelemetryFixtures {

    static final ModelInfo MODEL = new ModelInfo(
        ModelId.of("test", "model"), "Model", Set.of(), 0, 0, false,
        PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(),
        ModelCompat.NONE, "telemetry-test");

    static final String[] SECRETS = {
        "text-secret", "thinking-secret", "argument-secret",
        "call-secret", "deferred-secret",
    };

    private AiTelemetryFixtures() {
    }

    static StreamRequest request() {
        return new StreamRequest(MODEL, "sys", List.of(), List.of(), -1, -1,
            Optional.of(new ThinkingLevel.High()));
    }

    static Usage fullUsage() {
        return new Usage(11, 22, 33, 44, null, null, 110,
            new Usage.Cost(0.1, 0.2, 0.3, 0.4, 1));
    }

    static StreamEvent.UsageInfo usageInfo() {
        return new StreamEvent.UsageInfo(11, 22, null, fullUsage());
    }

    static AssistantMessage partial(StopReason stop, String errorMessage) {
        return new AssistantMessage(
            "msg", List.of(), usageInfo(), stop, "telemetry-test", "test", "model",
            null, null, null, Instant.ofEpochMilli(1_700_000_000_000L),
            errorMessage, null, null, "response");
    }

    /** 只有终局事件携带 usage／错误文本的车道形状（docs/13 §4.5 的回退输入）。 */
    static AssistantMessage partialWithoutUsage(StopReason stop) {
        return new AssistantMessage(
            "msg", List.of(), null, stop, "telemetry-test", "test", "model",
            null, null, null, Instant.ofEpochMilli(1_700_000_000_000L),
            null, null, null, "response");
    }

    static List<StreamEvent> events(StreamEvent terminal) {
        var result = partial(StopReason.STOP, null);
        return List.of(
            new StreamEvent.Start(result),
            new StreamEvent.TextDelta(0, "ignored-delta", result),
            new StreamEvent.TextEnd(0, "text-secret", result),
            new StreamEvent.ThinkingEnd(1, "thinking-secret", result),
            new StreamEvent.ToolCallDelta(2, "call-secret", "{\"value\":", result),
            new StreamEvent.ToolCallDelta(2, "call-secret", "\"argument-secret\"}", result),
            new StreamEvent.ToolCallEnd(2, "call-secret", "lookup",
                Map.of("value", "argument-secret"), result),
            terminal);
    }

    static StreamEvent.StreamDone done() {
        return new StreamEvent.StreamDone(
            StopReason.STOP, usageInfo(), partial(StopReason.STOP, null));
    }

    static AiRequestTelemetry.Observer observe(
        InMemoryTelemetryContext telemetry, TelemetrySpan parent, boolean poll) {
        return AiRequestTelemetry.observe(
            "test", "telemetry-test", "model", Optional.of(new ThinkingLevel.High()),
            parent, poll, System::nanoTime);
    }

    static void awaitSettled(InMemoryTelemetryContext telemetry) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (telemetry.getSpans().stream().allMatch(RecordedTelemetrySpan::settled)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("telemetry spans did not settle");
    }

    static List<StreamEvent> drain(
        AbstractChatApi api, StreamRequest request, ApiOptions options) {
        var received = new ArrayList<StreamEvent>();
        try (var iter = api.streamBlocking(request, options)) {
            while (iter.hasNext()) {
                received.add(iter.next());
            }
        }
        return received;
    }

    static ApiOptions options(TelemetrySpan parent) {
        return new ApiOptions("", "", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, parent);
    }

    static String json(Object value) {
        try {
            return new ObjectMapper().valueToTree(value).toString();
        } catch (RuntimeException e) {
            throw new AssertionError(e);
        }
    }

    /** {@code RecordedTelemetrySpan} 含 {@code OptionalLong}，单独序列化会缺 jdk8 模块。 */
    static String spanJson(RecordedTelemetrySpan span) {
        var shape = new java.util.LinkedHashMap<String, Object>();
        shape.put("name", span.name());
        shape.put("attributes", span.attributes());
        shape.put("events", span.events());
        shape.put("status", span.status().toString());
        return json(shape);
    }

    static void assertNoSecrets(String json) {
        for (var secret : SECRETS) {
            assertThat(json).doesNotContain(secret);
        }
    }

    /** 重放固定事件的适配器；可选在提交任何帧前抛异常。 */
    static final class ReplayApi extends AbstractChatApi {

        private final List<StreamEvent> events;
        private final RuntimeException failure;

        ReplayApi(List<StreamEvent> events) {
            this(events, null);
        }

        ReplayApi(List<StreamEvent> events, RuntimeException failure) {
            this.events = List.copyOf(events);
            this.failure = failure;
        }

        @Override
        public String apiName() {
            return "telemetry-test";
        }

        @Override
        protected void streamInternal(StreamRequest request,
                                      SubmissionPublisher<StreamEvent> publisher) {
            if (failure != null) {
                throw failure;
            }
            for (var event : events) {
                publisher.submit(event);
            }
        }
    }

    /** 在 Start 与 Done 之间等待放行的适配器（早关闭测试用）。 */
    static final class GatedApi extends AbstractChatApi {

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String apiName() {
            return "telemetry-test";
        }

        void release() {
            release.countDown();
        }

        @Override
        protected void streamInternal(StreamRequest request,
                                      SubmissionPublisher<StreamEvent> publisher) {
            publisher.submit(new StreamEvent.Start(partial(StopReason.PENDING, null)));
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var done = partial(StopReason.STOP, null);
            publisher.submit(new StreamEvent.StreamDone(StopReason.STOP, usageInfo(), done));
        }
    }

    /** 转发到真实 span，但替换 {@code metrics()} capability。 */
    static final class MetricsOverridingSpan implements TelemetrySpan {

        private final TelemetrySpan delegate;
        private final TelemetryMetrics metrics;

        MetricsOverridingSpan(TelemetrySpan delegate, TelemetryMetrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }

        @Override
        public <T> T startSpan(SpanOptions options,
                               Function<? super TelemetrySpan, ? extends T> body) {
            return delegate.startSpan(options, body);
        }

        @Override
        public TelemetrySpan openSpan(SpanOptions options) {
            return delegate.openSpan(options);
        }

        @Override
        public TelemetryMetrics metrics() {
            return metrics;
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void addEvent(String name, Map<String, Object> attributes) {
            delegate.addEvent(name, attributes);
        }

        @Override
        public void setAttributes(Map<String, Object> attributes) {
            delegate.setAttributes(attributes);
        }

        @Override
        public void setStatus(SpanStatus status) {
            delegate.setStatus(status);
        }
    }

    /** 统计 instrument 创建次数的 metrics 包装。 */
    static final class CountingMetrics implements TelemetryMetrics {

        private final InMemoryTelemetryContext delegate;
        private int histograms;
        private int counters;

        CountingMetrics(InMemoryTelemetryContext delegate) {
            this.delegate = delegate;
        }

        int histograms() {
            return histograms;
        }

        int counters() {
            return counters;
        }

        @Override
        public TelemetryCounter counter(String name, InstrumentOptions options) {
            counters++;
            return delegate.counter(name, options);
        }

        @Override
        public TelemetryUpDownCounter upDownCounter(String name, InstrumentOptions options) {
            return delegate.upDownCounter(name, options);
        }

        @Override
        public TelemetryHistogram histogram(String name, HistogramOptions options) {
            histograms++;
            return delegate.histogram(name, options);
        }
    }

    /** tracing-only 父跨度：事件仍写真实 span，但没有 metrics capability。 */
    static MetricsOverridingSpan tracingOnly(TelemetrySpan delegate) {
        return new MetricsOverridingSpan(delegate, NoopTelemetryMetrics.INSTANCE);
    }
}
