package com.pijava.protocol;

import java.util.List;
import java.util.Map;

/**
 * 会话快照（对齐 pi {@code SessionSnapshotSchema}）。时间戳为 epoch 毫秒。
 */
public record SessionSnapshot(
    String id,
    String name,
    String cwd,
    long createdAt,
    long updatedAt,
    SessionPhase phase,
    ModelRef model,
    ProtocolThinkingLevel thinkingLevel,
    boolean attached,
    boolean locked,
    long revision,
    List<TranscriptItem> transcript,
    List<Map<String, Object>> queuedSteer,
    int queuedSteerCount
) {
    /** Compact constructor that defensively copies the transcript and queues. */
    public SessionSnapshot {
        transcript = List.copyOf(transcript);
        queuedSteer = List.copyOf(queuedSteer);
    }
}
