package com.pijava.protocol;

import java.util.Map;

/**
 * 转录条目（对齐 pi {@code TranscriptItemSchema} 的扁平 wire 表示）。
 *
 * <p>{@code type} 判别（"message"/"toolCall"/"toolResult"/...），{@code data} 携带
 * 具体字段。P6-9a 以 Map 承载，server 适配层负责与 pi-java Entry 互转。</p>
 */
public record TranscriptItem(
    String type,
    Map<String, Object> data
) {
    /** Compact constructor that defensively copies the data map. */
    public TranscriptItem {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
