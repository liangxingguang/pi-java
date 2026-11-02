package com.pijava.telemetry;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared detached attribute copies for spans, events, and measurements. */
final class TelemetryAttributes {

    private TelemetryAttributes() {
    }

    /** pi {@code copyAttributes}: null values are absent and arrays are copied. */
    static Map<String, Object> copy(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : attributes.entrySet()) {
            if (entry.getValue() != null) {
                copy.put(entry.getKey(), copyValue(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        if (value.getClass().isArray()) {
            var length = Array.getLength(value);
            var copy = Array.newInstance(value.getClass().getComponentType(), length);
            System.arraycopy(value, 0, copy, 0, length);
            return copy;
        }
        return value;
    }
}
