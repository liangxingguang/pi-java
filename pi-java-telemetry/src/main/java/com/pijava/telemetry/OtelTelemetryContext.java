package com.pijava.telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;

/**
 * A {@link TelemetryContext} backed by the OpenTelemetry API (P6-20).
 *
 * <p>Spans become OTel spans, counters become {@code LongCounter}s, and timings
 * become {@code LongHistogram}s on the {@code "com.pijava"} instrumentation
 * scope. Callers supply a configured {@link OpenTelemetry} (typically an
 * {@code OpenTelemetrySdk} wired with an OTLP or console exporter); without
 * one the tracer/meter are no-ops, so nothing is recorded.</p>
 */
public final class OtelTelemetryContext implements TelemetryContext, TelemetryMetrics {

    /** Instrumentation scope name for all spans and metrics. */
    private static final String INSTRUMENTATION = "com.pijava";

    private final OpenTelemetry openTelemetry;
    private final Map<String, String> dimensions;

    private OtelTelemetryContext(OpenTelemetry openTelemetry, Map<String, String> dimensions) {
        this.openTelemetry = openTelemetry;
        this.dimensions = Map.copyOf(dimensions);
    }

    /**
     * Creates a context recording into the given OpenTelemetry instance.
     *
     * @param openTelemetry a configured OpenTelemetry (an {@code OpenTelemetrySdk})
     * @return the telemetry context
     */
    public static OtelTelemetryContext create(OpenTelemetry openTelemetry) {
        return new OtelTelemetryContext(openTelemetry, Map.of());
    }

    @Override
    public <T> T startSpan(SpanOptions options,
                           Function<? super TelemetrySpan, ? extends T> body) {
        try (var span = openSpan(options)) {
            try {
                return body.apply(span);
            } catch (RuntimeException | Error e) {
                if (span instanceof OtelSpan os) {
                    os.span.recordException(e);
                    os.span.setStatus(StatusCode.ERROR);
                }
                throw e;
            }
        }
    }

    @Override
    public TelemetrySpan openSpan(SpanOptions options) {
        var builder = openTelemetry.getTracer(INSTRUMENTATION).spanBuilder(options.name());
        Attributes attrs = toAttributes(options.attributes()).toBuilder()
            .putAll(dimensionAttributes())
            .build();
        Span span = builder.setAllAttributes(attrs).startSpan();
        return new OtelSpan(span);
    }

    @Override
    public TelemetryContext with(String key, String value) {
        var merged = new HashMap<>(dimensions);
        merged.put(key, value);
        return new OtelTelemetryContext(openTelemetry, merged);
    }

    @Override
    public void incrementCounter(String name, long delta) {
        openTelemetry.getMeter(INSTRUMENTATION).counterBuilder(name).build()
            .add(delta, dimensionAttributes());
    }

    @Override
    public void recordTiming(String name, long durationMs) {
        openTelemetry.getMeter(INSTRUMENTATION).histogramBuilder(name).ofLongs().build()
            .record(durationMs, dimensionAttributes());
    }

    @Override
    public TelemetryMetrics metrics() {
        return this;
    }

    @Override
    public TelemetryCounter counter(String name, InstrumentOptions options) {
        var builder = openTelemetry.getMeter(INSTRUMENTATION).counterBuilder(name).ofDoubles();
        if (options != null) {
            if (options.description() != null) {
                builder.setDescription(options.description());
            }
            if (options.unit() != null) {
                builder.setUnit(options.unit());
            }
        }
        var counter = builder.build();
        return (value, attributes) -> counter.add(value, metricAttributes(attributes));
    }

    @Override
    public TelemetryUpDownCounter upDownCounter(String name, InstrumentOptions options) {
        var builder = openTelemetry.getMeter(INSTRUMENTATION).upDownCounterBuilder(name).ofDoubles();
        if (options != null) {
            if (options.description() != null) {
                builder.setDescription(options.description());
            }
            if (options.unit() != null) {
                builder.setUnit(options.unit());
            }
        }
        var counter = builder.build();
        return (value, attributes) -> counter.add(value, metricAttributes(attributes));
    }

    @Override
    public TelemetryHistogram histogram(String name, HistogramOptions options) {
        var builder = openTelemetry.getMeter(INSTRUMENTATION).histogramBuilder(name);
        if (options != null) {
            if (options.description() != null) {
                builder.setDescription(options.description());
            }
            if (options.unit() != null) {
                builder.setUnit(options.unit());
            }
            if (options.boundaries() != null) {
                builder.setExplicitBucketBoundariesAdvice(options.boundaries());
            }
        }
        var histogram = builder.build();
        return (value, attributes, context) -> {
            var attrs = metricAttributes(attributes);
            if (context instanceof OtelSpan span) {
                histogram.record(value, attrs, Context.root().with(span.span));
            } else {
                histogram.record(value, attrs);
            }
        };
    }

    private Attributes metricAttributes(Map<String, Object> attributes) {
        return toAttributes(attributes).toBuilder().putAll(dimensionAttributes()).build();
    }

    private TelemetrySpan openSpanUnder(Span parent, SpanOptions options) {
        var builder = openTelemetry.getTracer(INSTRUMENTATION).spanBuilder(options.name());
        builder.setParent(Context.root().with(parent));
        Attributes attrs = toAttributes(options.attributes()).toBuilder()
            .putAll(dimensionAttributes())
            .build();
        return new OtelSpan(builder.setAllAttributes(attrs).startSpan());
    }

    private Attributes dimensionAttributes() {
        var builder = Attributes.builder();
        dimensions.forEach(builder::put);
        return builder.build();
    }

    /** Converts supported scalar and array values to OTel attributes. */
    private static Attributes toAttributes(Map<String, Object> attrs) {
        var builder = Attributes.builder();
        for (var entry : attrs.entrySet()) {
            putAttribute(builder, entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private static void putAttribute(AttributesBuilder builder, String key, Object value) {
        if (value instanceof String s) {
            builder.put(key, s);
        } else if (value instanceof Boolean b) {
            builder.put(key, b);
        } else if (value instanceof Long l) {
            builder.put(key, l);
        } else if (value instanceof Integer i) {
            builder.put(key, i.longValue());
        } else if (value instanceof Double d) {
            builder.put(key, d);
        } else if (value instanceof String[] array) {
            builder.put(AttributeKey.stringArrayKey(key), Arrays.asList(array));
        } else if (value instanceof long[] array) {
            builder.put(AttributeKey.longArrayKey(key), Arrays.stream(array).boxed().toList());
        } else if (value instanceof double[] array) {
            builder.put(AttributeKey.doubleArrayKey(key), Arrays.stream(array).boxed().toList());
        } else if (value instanceof boolean[] array) {
            var values = new ArrayList<Boolean>(array.length);
            for (boolean element : array) {
                values.add(element);
            }
            builder.put(AttributeKey.booleanArrayKey(key), values);
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            putListAttribute(builder, key, list);
        }
        // Unsupported attribute types are skipped (telemetry is best-effort).
    }

    private static void putListAttribute(AttributesBuilder builder, String key, List<?> list) {
        var first = list.getFirst();
        if (first instanceof String) {
            builder.put(AttributeKey.stringArrayKey(key), list.stream().map(String.class::cast).toList());
        } else if (first instanceof Boolean) {
            builder.put(AttributeKey.booleanArrayKey(key), list.stream().map(Boolean.class::cast).toList());
        } else if (first instanceof Byte || first instanceof Short
            || first instanceof Integer || first instanceof Long
        ) {
            builder.put(AttributeKey.longArrayKey(key),
                list.stream().map(value -> ((Number) value).longValue()).toList());
        } else if (first instanceof Float || first instanceof Double) {
            builder.put(AttributeKey.doubleArrayKey(key),
                list.stream().map(value -> ((Number) value).doubleValue()).toList());
        }
    }

    /** A span that doubles as a context for nested child spans and metrics. */
    private final class OtelSpan implements TelemetrySpan {
        private final Span span;
        private boolean ended;

        private OtelSpan(Span span) {
            this.span = span;
        }

        @Override
        public void close() {
            if (!ended) {
                ended = true;
                span.end();
            }
        }

        @Override
        public void setAttributes(Map<String, Object> attributes) {
            if (ended) {
                return;
            }
            for (var entry : attributes.entrySet()) {
                var key = entry.getKey();
                var value = entry.getValue();
                if (value instanceof String s) {
                    span.setAttribute(key, s);
                } else if (value instanceof Boolean b) {
                    span.setAttribute(key, b);
                } else if (value instanceof Long l) {
                    span.setAttribute(key, l);
                } else if (value instanceof Integer i) {
                    span.setAttribute(key, (long) i);
                } else if (value instanceof Double d) {
                    span.setAttribute(key, d);
                }
            }
        }

        /**
         * pi {@code index.ts:19} 的 OTel 映射：跨度事件 → OTel span event。
         * settled 后 inert；记录被动（docs/23 §2）。
         */
        @Override
        public void addEvent(String name, Map<String, Object> attributes) {
            if (ended) {
                return;
            }
            try {
                span.addEvent(name, toAttributes(attributes));
            } catch (RuntimeException e) {
                // Recording is passive (pi memory.ts:145-147).
            }
        }

        /**
         * pi {@code index.ts:21} 的 OTel 映射：Error 带详情时压成
         * {@code "name: message"} description。注意 OTel SDK 自有状态语义
         * （OK 一旦设置不可被 ERROR 覆盖）与 pi 的「后写赢」不同——OTel 适配器
         * 是扩展实现（docs/23 §7.4），不挂一致性套件。
         */
        @Override
        public void setStatus(SpanStatus newStatus) {
            if (ended) {
                return;
            }
            switch (Objects.requireNonNull(newStatus, "status")) {
                case SpanStatus.Ok ok -> span.setStatus(StatusCode.OK);
                case SpanStatus.Error error -> {
                    if (error.name() != null) {
                        span.setStatus(StatusCode.ERROR, error.name() + ": " + error.message());
                    } else {
                        span.setStatus(StatusCode.ERROR);
                    }
                }
            }
        }

        @Override
        public <T> T startSpan(SpanOptions options,
                               Function<? super TelemetrySpan, ? extends T> body) {
            try (var child = openSpanUnder(this.span, options)) {
                try {
                    return body.apply(child);
                } catch (RuntimeException | Error e) {
                    if (child instanceof OtelSpan os) {
                        os.span.recordException(e);
                        os.span.setStatus(StatusCode.ERROR);
                    }
                    throw e;
                }
            }
        }

        @Override
        public TelemetryMetrics metrics() {
            return OtelTelemetryContext.this;
        }

        @Override
        public void incrementCounter(String name, long delta) {
            OtelTelemetryContext.this.incrementCounter(name, delta);
        }

        @Override
        public void recordTiming(String name, long durationMs) {
            OtelTelemetryContext.this.recordTiming(name, durationMs);
        }

        @Override
        public TelemetryContext with(String key, String value) {
            return OtelTelemetryContext.this.with(key, value);
        }
    }
}
