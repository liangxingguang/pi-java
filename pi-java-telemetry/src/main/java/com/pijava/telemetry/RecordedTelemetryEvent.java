package com.pijava.telemetry;

import java.util.Map;

/**
 * pi {@code memory.ts:11-14}：一条录制在跨度上的事件。
 * attributes 为不可变副本（detached）。
 */
public record RecordedTelemetryEvent(String name, Map<String, Object> attributes) {

    /** Defensively copy attributes. */
    public RecordedTelemetryEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
