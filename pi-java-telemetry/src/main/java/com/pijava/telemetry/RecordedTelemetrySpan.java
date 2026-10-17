package com.pijava.telemetry;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * pi {@code memory.ts:16-25}：一条录制跨度的 detached 快照。
 *
 * @param id         跨度 id（自 1 自增，按开启序）
 * @param parentId   父跨度 id；null ≙ pi 的 {@code null}（根跨度）
 * @param name       跨度名
 * @param attributes 属性（不可变副本）
 * @param events     事件（不可变副本，按记录序）
 * @param status     状态（显式或自动派生）
 * @param settled    是否已 settle
 * @param endSequence 结束序号（自 1 自增）；empty ≙ pi 的键缺席（未 settle）
 */
public record RecordedTelemetrySpan(
    long id,
    Long parentId,
    String name,
    Map<String, Object> attributes,
    List<RecordedTelemetryEvent> events,
    SpanStatus status,
    boolean settled,
    OptionalLong endSequence
) {

    /** Defensively copy attributes and events. */
    public RecordedTelemetrySpan {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
