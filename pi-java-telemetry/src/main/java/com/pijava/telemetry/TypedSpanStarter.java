package com.pijava.telemetry;

import java.util.Map;
import java.util.function.BiFunction;

/** A span starter bound to one explicit parent and a child-starter callback. */
@FunctionalInterface
public interface TypedSpanStarter {

    /** Start a span and supply a starter bound to that span for nested calls. */
    <T> T startSpan(
        String name,
        Map<String, Object> attributes,
        BiFunction<? super TelemetrySpan, ? super TypedSpanStarter, ? extends T> callback
    );
}
