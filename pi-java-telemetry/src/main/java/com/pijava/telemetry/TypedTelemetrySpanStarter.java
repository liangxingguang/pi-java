package com.pijava.telemetry;

import java.util.Map;
import java.util.function.BiFunction;

/** Runtime binder; schema values intentionally play no runtime validation role. */
final class TypedTelemetrySpanStarter {

    private TypedTelemetrySpanStarter() {
    }

    static TypedSpanStarter bind(TelemetryContext parent) {
        return new TypedSpanStarter() {
            @Override
            public <T> T startSpan(
                String name,
                Map<String, Object> attributes,
                BiFunction<? super TelemetrySpan, ? super TypedSpanStarter, ? extends T> callback
            ) {
                return parent.startSpan(
                    new SpanOptions(name, attributes),
                    span -> callback.apply(span, bind(span)));
            }
        };
    }
}
