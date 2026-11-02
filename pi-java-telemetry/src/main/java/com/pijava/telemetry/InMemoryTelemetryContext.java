package com.pijava.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * pi {@code memory.ts:188-219} 的移植：后端中立的参考实现，把跨度记录在进程
 * 内存。每个实例隔离一个录制域（测试 / 独立录制范围各建各的）。
 *
 * <p>语义逐条对 pi：跨度 id 自 1 自增（{@code memory.ts:195}）；endSequence 自 1
 * 自增、仅 settle 时赋值（{@code :98}）；settled 后 {@code addEvent} /
 * {@code setAttributes} / {@code setStatus} / {@code close} 全 inert
 * （{@code :142,150,158,95}）；settled 父跨度的 {@code startSpan}/{@code openSpan}
 * 降级 {@link NoopTelemetryContext}（{@code :126}）；记录被动——属性读取失败整包
 * 原子忽略（{@code :149-156}）；失败时无显式状态才自动派生 error
 * （{@code :96}）；{@link #getSpans()} 返回 detached 深拷贝快照（{@code :203-218}）。</p>
 *
 * <p>java 偏差：pi 单线程无锁；此处 state 以 {@link #lock} 收口（回调在锁外执行，
 * 锁只护录制状态），语义不变。</p>
 */
public final class InMemoryTelemetryContext implements TelemetryContext, TelemetryMetrics {

    private static final Object CARRIER_LOCK = new Object();
    private static final Map<TelemetryContext, Map<String, String>> CONTEXT_CARRIERS = new WeakHashMap<>();

    private final Object lock = new Object();
    private final List<MutableSpan> spans = new ArrayList<>();
    private final List<RecordedTelemetryMeasurement> measurements = new ArrayList<>();
    private final String scope = UUID.randomUUID().toString();
    private long nextSpanId = 1;
    private long nextEndSequence = 1;

    @Override
    public <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body) {
        return startInMemorySpan(null, options, body);
    }

    /**
     * 覆写默认实现：默认的 {@code openSpan} 经 {@code startSpan} 会在回调返回时
     把跨度提前 settle；{@code openSpan} 必须返回真正未 settle 的跨度。
     */
    @Override
    public TelemetrySpan openSpan(SpanOptions options) {
        return createSpan(null, options);
    }

    @Override
    public TelemetryMetrics metrics() {
        return this;
    }

    @Override
    public TelemetryCounter counter(String name, InstrumentOptions options) {
        String description = options == null ? null : options.description();
        String unit = options == null ? null : options.unit();
        return (value, attributes) -> recordMeasurement(
            MetricKind.COUNTER, name, description, unit, null, value, attributes, null);
    }

    @Override
    public TelemetryUpDownCounter upDownCounter(String name, InstrumentOptions options) {
        String description = options == null ? null : options.description();
        String unit = options == null ? null : options.unit();
        return (value, attributes) -> recordMeasurement(
            MetricKind.UP_DOWN_COUNTER, name, description, unit, null, value, attributes, null);
    }

    @Override
    public TelemetryHistogram histogram(String name, HistogramOptions options) {
        String description = options == null ? null : options.description();
        String unit = options == null ? null : options.unit();
        List<Double> boundaries = options == null || options.boundaries() == null
            ? null : List.copyOf(options.boundaries());
        return (value, attributes, context) -> recordMeasurement(
            MetricKind.HISTOGRAM, name, description, unit, boundaries, value, attributes, context);
    }

    private void recordMeasurement(
        MetricKind kind,
        String name,
        String description,
        String unit,
        List<Double> boundaries,
        double value,
        Map<String, Object> attributes,
        TelemetryContext context
    ) {
        synchronized (lock) {
            Map<String, String> carrier;
            synchronized (CARRIER_LOCK) {
                carrier = context == null ? null : CONTEXT_CARRIERS.get(context);
            }
            measurements.add(new RecordedTelemetryMeasurement(
                kind, name, description, unit, boundaries, value, attributes, carrier));
        }
    }

    /** Return raw measurements in recording order as detached snapshots. */
    public List<RecordedTelemetryMeasurement> getMetrics() {
        synchronized (lock) {
            return measurements.stream()
                .map(measurement -> new RecordedTelemetryMeasurement(
                    measurement.kind(), measurement.name(), measurement.description(), measurement.unit(),
                    measurement.boundaries(), measurement.value(), measurement.attributes(), measurement.context()))
                .toList();
        }
    }

    /** pi {@code getSpans()}（{@code memory.ts:203-218}）：detached 快照，按开启序。 */
    public List<RecordedTelemetrySpan> getSpans() {
        synchronized (lock) {
            var snapshot = new ArrayList<RecordedTelemetrySpan>(spans.size());
            for (var span : spans) {
                snapshot.add(span.snapshot());
            }
            return List.copyOf(snapshot);
        }
    }

    private <T> T startInMemorySpan(
        MutableSpan parent, SpanOptions options, Function<? super TelemetrySpan, ? extends T> body
    ) {
        // pi memory.ts:126 —— settled 父跨度的子跨度降级 NOOP。
        if (parent != null && parent.isSettled()) {
            return NoopTelemetryContext.startInertSpan(this, options, body);
        }
        MutableSpan span;
        try {
            span = createSpan(parent, options);
        } catch (RuntimeException | Error e) {
            // pi memory.ts:128-134 —— 录制失败降级 NOOP，回调仍执行。
            return NoopTelemetryContext.startInertSpan(this, options, body);
        }
        try {
            T result = body.apply(span);
            span.settle(false, null);
            return result;
        } catch (RuntimeException | Error e) {
            span.settle(true, e);
            throw e;
        }
    }

    private MutableSpan createSpan(MutableSpan parent, SpanOptions options) {
        synchronized (lock) {
            var span = new MutableSpan(nextSpanId++, parent, options);
            spans.add(span);
            return span;
        }
    }

    /** pi {@code automaticErrorStatus}（{@code memory.ts:78-87}）：被动检查。 */
    private static SpanStatus automaticErrorStatus(Throwable error) {
        try {
            if (error != null) {
                return new SpanStatus.Error(
                    error.getClass().getSimpleName(),
                    Objects.requireNonNullElse(error.getMessage(), ""));
            }
        } catch (RuntimeException | Error e) {
            // Error inspection is passive. Fall through to an error status
            // without details.
        }
        return new SpanStatus.Error(null, null);
    }

    /** 内部可变跨度（pi {@code MutableRecordedTelemetrySpan}，{@code memory.ts:32-42}）。 */
    private final class MutableSpan implements TelemetrySpan {

        private final long id;
        private final MutableSpan parent;
        private final Map<String, String> context;
        private final Map<String, String> parentContext;
        private final String name;
        private final Map<String, Object> attributes;
        private final List<RecordedTelemetryEvent> events = new ArrayList<>();
        private SpanStatus status = new SpanStatus.Ok();
        private boolean explicitStatus;
        private boolean settled;
        private long endSequence = -1;

        MutableSpan(long id, MutableSpan parent, SpanOptions options) {
            this.id = id;
            this.parent = parent;
            this.context = Map.of(
                "pi-memory-scope", scope,
                "pi-memory-span", Long.toString(id),
                "pi-memory-trace", parent == null
                    ? scope + ":" + id : parent.context.get("pi-memory-trace"));
            this.parentContext = parent == null ? null : parent.context;
            this.name = options.name();
            this.attributes = new LinkedHashMap<>(TelemetryAttributes.copy(options.attributes()));
            synchronized (CARRIER_LOCK) {
                CONTEXT_CARRIERS.put(this, context);
            }
        }

        boolean isSettled() {
            synchronized (lock) {
                return settled;
            }
        }

        @Override
        public <T> T startSpan(
            SpanOptions options, Function<? super TelemetrySpan, ? extends T> body
        ) {
            return startInMemorySpan(this, options, body);
        }

        @Override
        public TelemetryMetrics metrics() {
            return InMemoryTelemetryContext.this;
        }

        @Override
        public TelemetrySpan openSpan(SpanOptions options) {
            if (isSettled()) {
                // pi memory.ts:126 —— settled 父跨度的子跨度降级 NOOP。
                return NoopTelemetryContext.openInertSpan(InMemoryTelemetryContext.this);
            }
            return createSpan(this, options);
        }

        @Override
        public void addEvent(String name, Map<String, Object> eventAttributes) {
            synchronized (lock) {
                if (settled) {
                    return;
                }
                try {
                    events.add(new RecordedTelemetryEvent(name, TelemetryAttributes.copy(eventAttributes)));
                } catch (RuntimeException | Error e) {
                    // Recording is passive (pi memory.ts:145-147).
                }
            }
        }

        @Override
        public void setAttributes(Map<String, Object> newAttributes) {
            synchronized (lock) {
                if (settled) {
                    return;
                }
                try {
                    // 两段式：先建合并副本再替换，失败整包原子忽略
                    // （pi memory.ts:63-69,149-156）。
                    var merged = new LinkedHashMap<>(attributes);
					merged.putAll(TelemetryAttributes.copy(newAttributes));
                    attributes.clear();
                    attributes.putAll(merged);
                } catch (RuntimeException | Error e) {
                    // Recording is passive (pi memory.ts:153-155).
                }
            }
        }

        @Override
        public void setStatus(SpanStatus newStatus) {
            synchronized (lock) {
                if (settled) {
                    return;
                }
                // pi 的 try/catch 针对 Proxy 载荷（memory.ts:161-163）；java 的
                // SpanStatus 是值语义的 sealed record，不可「读取失败」，null 是
                // 契约外用法 ⇒ fail fast（docs/23 §4.2 不可表达子场景登记）。
                status = Objects.requireNonNull(newStatus, "status");
                explicitStatus = true;
            }
        }

        /** pi {@code settleSpan}（{@code memory.ts:89-99}）：幂等。 */
        void settle(boolean failed, Throwable error) {
            synchronized (lock) {
                if (settled) {
                    return;
                }
                if (failed && !explicitStatus) {
                    status = automaticErrorStatus(error);
                }
                settled = true;
                endSequence = nextEndSequence++;
            }
        }

        @Override
        public void close() {
            settle(false, null);
        }

        /** pi {@code getSpans()} 的单跨度深拷贝（{@code memory.ts:205-217}）。 */
        RecordedTelemetrySpan snapshot() {
            synchronized (lock) {
                return new RecordedTelemetrySpan(
                    id,
                    parent != null ? parent.id : null,
                    context,
                    parentContext,
                    name,
                    TelemetryAttributes.copy(attributes),
                    events.stream()
                        .map(event -> new RecordedTelemetryEvent(event.name(), event.attributes()))
                        .toList(),
                    status,
                    settled,
                    settled ? OptionalLong.of(endSequence) : OptionalLong.empty());
            }
        }
    }
}
