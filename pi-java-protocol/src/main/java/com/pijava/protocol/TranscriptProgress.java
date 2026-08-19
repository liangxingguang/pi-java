package com.pijava.protocol;

import java.util.Map;

/**
 * 转录进度事件（对齐 pi {@code TranscriptProgressSchema} 的扁平 wire 表示）。
 */
public record TranscriptProgress(
    String type,
    Map<String, Object> data
) {
    /** Compact constructor that defensively copies the data map. */
    public TranscriptProgress {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
