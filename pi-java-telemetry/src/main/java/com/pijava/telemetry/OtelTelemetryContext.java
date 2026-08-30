package com.pijava.telemetry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
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
public final class OtelTelemetryContext implements TelemetryContext {

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

    /** Converts a String/Long/Integer/Double/Boolean map to OTel attributes. */
    private static Attributes toAttributes(Map<String, Object> attrs) {
        var builder = Attributes.builder();
        for (var entry : attrs.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                builder.put(entry.getKey(), s);
            } else if (value instanceof Boolean b) {
                builder.put(entry.getKey(), b);
            } else if (value instanceof Long l) {
                builder.put(entry.getKey(), l);
            } else if (value instanceof Integer i) {
                builder.put(entry.getKey(), i.longValue());
            } else if (value instanceof Double d) {
                builder.put(entry.getKey(), d);
            }
            // Unsupported attribute types are skipped (telemetry is best-effort).
        }
        return builder.build();
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
        public void addAttribute(String key, Object value) {
            if (ended) {
                return;
            }
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
