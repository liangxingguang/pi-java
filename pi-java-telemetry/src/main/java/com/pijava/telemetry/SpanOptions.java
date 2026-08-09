package com.pijava.telemetry;

import java.util.Map;

/**
 * Options for creating a telemetry span.
 *
 * @param name       the span name (e.g. "ai.call", "tool.bash")
 * @param attributes key-value pairs attached to the span
 */
public record SpanOptions(String name, Map<String, Object> attributes) {

    /** Create span options with a name and no attributes. */
    public SpanOptions(String name) {
        this(name, Map.of());
    }

    public SpanOptions {
        attributes = Map.copyOf(attributes);
    }
}
